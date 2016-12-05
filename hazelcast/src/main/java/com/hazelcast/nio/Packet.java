/*
 * Copyright (c) 2008-2016, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.nio;

import com.hazelcast.internal.serialization.impl.HeapData;
import com.hazelcast.spi.annotation.PrivateApi;

import java.nio.ByteBuffer;

import static com.hazelcast.nio.Bits.BYTE_SIZE_IN_BYTES;
import static com.hazelcast.nio.Bits.INT_SIZE_IN_BYTES;
import static com.hazelcast.nio.Bits.SHORT_SIZE_IN_BYTES;

/**
 * A Packet is a piece of data sent over the wire. The Packet is used for member to member communication.
 *
 * The Packet extends HeapData instead of wrapping it. From a design point of view this is often
 * not the preferred solution (prefer composition over inheritance), but in this case that
 * would mean more object litter.
 *
 * Since the Packet isn't used throughout the system, this design choice is visible locally.
 */
@PrivateApi
public final class Packet extends HeapData implements OutboundFrame {

    public static final byte VERSION = 4;


    //             PACKET HEADER FLAGS
    //
    // Flags are dispatched against in a cascade:
    // 1. URGENT (bit 4)
    // 2. Packet type (bits 0, 2, 5)
    // 3. Flags specific to a given packet type (bits 1, 6)


    // 1. URGENT flag

    /** Marks the packet as Urgent  */
    public static final int FLAG_URGENT = 1 << 4;


    // 2. Packet type flags, encode up to 7 packet types.
    //
    // When adding a new packet type, DO NOT ADD MORE TYPE FLAGS. Instead rename one of the
    // Packet.Type.UNDEFINEDx members to represent the new type.
    //
    // Historically the first three packet types were encoded as separate, mutually exclusive flags.
    // These are given below. The enum Packet.Type should be used to encode/decode the type from the
    // header flags bitfield.

    /** Packet type bit 0. Historically the OPERATION type flag. */
    public static final int FLAG_OP = 1 << 0;
    /** Packet type bit 1. Historically the EVENT type flag. */
    public static final int FLAG_EVENT = 1 << 2;
    /** Packet type bit 2. Historically the BIND type flag. */
    public static final int FLAG_BIND = 1 << 5;

    // 3. Type-specific flags. Same bits can be reused within each type

    // 3.a Operation packet flags

    /** Marks an Operation packet as Response */
    public static final int FLAG_OP_RESPONSE = 1 << 1;
    /** Marks an Operation packet as Operation control (like invocation-heartbeats) */
    public static final int FLAG_OP_CONTROL = 1 << 6;


    // 3.b Jet packet flags

    /** Marks a Jet packet as Flow control */
    public static final int FLAG_JET_FLOW_CONTROL = 1 << 1;


    //            END OF HEADER FLAG SECTION



    private static final int HEADER_SIZE = BYTE_SIZE_IN_BYTES + SHORT_SIZE_IN_BYTES + INT_SIZE_IN_BYTES + INT_SIZE_IN_BYTES;

    // char is a 16-bit unsigned integer. Here we use it as a bitfield.
    private char flags;

    private int partitionId;
    private transient Connection conn;

    // These 3 fields are only used during read/write. Otherwise they have no meaning.
    private int valueOffset;
    private int size;
    // Stores the current 'phase' of read/write. This is needed so that repeated calls can be made to read/write.
    private boolean headerComplete;

    public Packet() {
    }

    public Packet(byte[] payload) {
        this(payload, -1);
    }

    public Packet(byte[] payload, int partitionId) {
        super(payload);
        this.partitionId = partitionId;
    }

    /**
     * Gets the Connection this Packet was send with.
     *
     * @return the Connection. Could be null.
     */
    public Connection getConn() {
        return conn;
    }

    /**
     * Sets the Connection this Packet is send with.
     * <p/>
     * This is done on the reading side of the Packet to make it possible to retrieve information about
     * the sender of the Packet.
     *
     * @param conn the connection.
     */
    public Packet setConn(Connection conn) {
        this.conn = conn;
        return this;
    }

    public Type getPacketType() {
        return Type.fromFlags(flags);
    }

    public Packet setPacketType(Packet.Type type) {
        int nonTypeFlags = flags & ~FLAG_OP & ~FLAG_EVENT & ~FLAG_BIND;
        resetFlagsTo(type.headerEncoding | nonTypeFlags);
        return this;
    }

    /**
     * Raises all the flags raised in the argument. Does not lower any flags.
     *
     * @param flagsToRaise the flags to raise
     * @return this (for fluent interface)
     */
    public Packet raiseFlags(int flagsToRaise) {
        flags |= flagsToRaise;
        return this;
    }

    /**
     * Sets all the flags to the state they have in the supplied argument.
     *
     * @param flagsToSet the flags. Only the least significant two bytes of the argument are used.
     * @return this (for fluent interface)
     */
    public Packet resetFlagsTo(int flagsToSet) {
        flags = (char) flagsToSet;
        return this;
    }

    /**
     * Returns {@code true} if any of the flags supplied in the argument are set.
     *
     * @param flagsToCheck the flags to check
     * @return {@code true} if any of the flags is set, {@code false} otherwise.
     */
    public boolean isFlagSet(int flagsToCheck) {
        return isFlagSet(flags, flagsToCheck);
    }

    private static boolean isFlagSet(char flags, int flagsToCheck) {
        return (flags & flagsToCheck) != 0;
    }

    /**
     * @return the complete flags bitfield as a {@code char}.
     */
    public char getFlags() {
        return flags;
    }

    /**
     * Returns the partition id of this packet. If this packet is not for a particular partition, -1 is returned.
     *
     * @return the partition id.
     */
    public int getPartitionId() {
        return partitionId;
    }

    public void reset() {
        headerComplete = false;
    }

    @Override
    public boolean isUrgent() {
        return isFlagSet(FLAG_URGENT);
    }

    public boolean writeTo(ByteBuffer dst) {
        if (!headerComplete) {
            if (dst.remaining() < HEADER_SIZE) {
                return false;
            }

            dst.put(VERSION);
            dst.putChar(flags);
            dst.putInt(partitionId);
            size = totalSize();
            dst.putInt(size);
            headerComplete = true;
        }

        return writeValue(dst);
    }

    /**
     * Reads the packet data from the supplied {@code ByteBuffer}. The buffer may not contain the complete packet.
     * If this method returns {@code false}, it should be called again to read more packet data.
     * @param src the source byte buffer
     * @return {@code true} if all the packet's data is now read; {@code false} otherwise.
     */
    public boolean readFrom(ByteBuffer src) {
        if (!headerComplete) {
            if (src.remaining() < HEADER_SIZE) {
                return false;
            }

            byte version = src.get();
            if (VERSION != version) {
                throw new IllegalArgumentException("Packet versions are not matching! Expected -> "
                        + VERSION + ", Incoming -> " + version);
            }

            flags = src.getChar();
            partitionId = src.getInt();
            size = src.getInt();
            headerComplete = true;
        }

        return readValue(src);
    }

    // ========================= value =================================================

    private boolean readValue(ByteBuffer src) {
        if (payload == null) {
            payload = new byte[size];
        }

        if (size > 0) {
            int bytesReadable = src.remaining();

            int bytesNeeded = size - valueOffset;

            boolean done;
            int bytesRead;
            if (bytesReadable >= bytesNeeded) {
                bytesRead = bytesNeeded;
                done = true;
            } else {
                bytesRead = bytesReadable;
                done = false;
            }

            // read the data from the byte-buffer into the bytes-array.
            src.get(payload, valueOffset, bytesRead);
            valueOffset += bytesRead;

            if (!done) {
                return false;
            }
        }

        return true;
    }

    private boolean writeValue(ByteBuffer dst) {
        if (size > 0) {
            // the number of bytes that can be written to the bb.
            int bytesWritable = dst.remaining();

            // the number of bytes that need to be written.
            int bytesNeeded = size - valueOffset;

            int bytesWrite;
            boolean done;
            if (bytesWritable >= bytesNeeded) {
                // All bytes for the value are available.
                bytesWrite = bytesNeeded;
                done = true;
            } else {
                // Not all bytes for the value are available. So lets write as much as is available.
                bytesWrite = bytesWritable;
                done = false;
            }

            byte[] byteArray = toByteArray();
            dst.put(byteArray, valueOffset, bytesWrite);
            valueOffset += bytesWrite;

            if (!done) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns an estimation of the packet, including its payload, in bytes.
     *
     * @return the size of the packet.
     */
    public int packetSize() {
        return (payload != null ? totalSize() : 0) + HEADER_SIZE;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof Packet)) {
            return false;
        }

        Packet packet = (Packet) o;
        if (!super.equals(packet)) {
            return false;
        }

        if (flags != packet.flags) {
            return false;
        }
        return partitionId == packet.partitionId;
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        result = 31 * result + (int) flags;
        result = 31 * result + partitionId;
        return result;
    }

    @Override
    public String toString() {
        final Type type = getPacketType();
        return "Packet{"
                + "partitionId=" + partitionId
                + ", conn=" + conn
                + ", rawFlags=" + Integer.toBinaryString(flags)
                + ", isUrgent=" + isUrgent()
                + ", packetType=" + type.name()
                + ", typeSpecificFlags=" + type.describeFlags(flags)
                + '}';
    }


    public enum Type {
        /**
         * Represents "missing packet type", consists of all zeros. A zeroed-out packet header would
         * resolve to this type.
         * <p>
         * {@code ordinal = 0}
         */
        NULL,
        /**
         * The type of an Operation packet.
         * <p>
         * {@code ordinal = 1}
         */
        OPERATION {
            @Override
            public String describeFlags(char flags) {
                return "[isResponse=" + isFlagSet(flags, FLAG_OP_RESPONSE)
                        + ", isOpControl=" + isFlagSet(flags, FLAG_OP_CONTROL) + ']';
            }
        },
        /**
         * The type of an Event packet.
         * <p>
         * {@code ordinal = 2}
         */
        EVENT,
        /**
         * The type of a Jet packet.
         * <p>
         *  {@code ordinal = 3}
         */
        JET {
            @Override
            public String describeFlags(char flags) {
                return "[isFlowControl=" + isFlagSet(flags, FLAG_JET_FLOW_CONTROL) + ']';
            }
        },
        /**
         * The type of a Bind Message packet.
         * <p>
         * {@code ordinal = 4}
         */
        BIND,
        /**
         * Unused packet type. Available for future use.
         * <p>
         * {@code ordinal = 5}
         */
        UNDEFINED5,
        /**
         * Unused packet type. Available for future use.
         * <p>
         * {@code ordinal = 6}
         */
        UNDEFINED6,
        /**
         * Unused packet type. Available for future use.
         * <p>
         * {@code ordinal = 7}
         */
        UNDEFINED7;

        final char headerEncoding;

        Type() {
            headerEncoding = (char) encodeOrdinal();
        }

        public static Type fromFlags(int flags) {
            return values()[headerDecode(flags)];
        }

        public String describeFlags(char flags) {
            return "<NONE>";
        };

        @SuppressWarnings("checkstyle:booleanexpressioncomplexity")
        private int encodeOrdinal() {
            final int ordinal = ordinal();
            assert ordinal < 8 : "Ordinal out of range for member " + name() + ": " + ordinal;
            return (ordinal & 0x01)
                 | (ordinal & 0x02) << 1
                 | (ordinal & 0x04) << 3;
        }

        @SuppressWarnings("checkstyle:booleanexpressioncomplexity")
        private static int headerDecode(int flags) {
            return (flags & FLAG_OP)
                 | (flags & FLAG_EVENT) >> 1
                 | (flags & FLAG_BIND) >> 3;
        }
    }
}
