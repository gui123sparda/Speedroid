package org.clonetuner.comm;

import org.clonetuner.ini.IniModel;
import org.clonetuner.model.Ecu;
import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import static org.junit.Assert.*;

public class StreamLinkTest {
    @Test public void readsFragmentedBluetoothOrUsbFramesAndChecksCrc() throws Exception {
        IniModel ini = new IniModel();
        FakeIo io = new FakeIo();
        StreamLink link = new StreamLink(ini, io);
        link.connect();
        io.reply = SerialProtocol.frame(new byte[] {0,'t','e','s','t'});
        assertEquals("test", link.signature());
        assertArrayEquals(new byte[] {0,1,'Q',(byte)0xce,0x6e,(byte)0x8e,(byte)0xef}, io.sent.toByteArray());
        io.position = 0;
        io.reply[io.reply.length-1] ^= 1;
        try { link.signature(); fail("Corrupt CRC accepted"); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("CRC")); }
        link.disconnect();
        assertTrue(io.closed);
    }
    @Test public void failedUnderlyingPortStillGetsClosed() throws Exception {
        FakeIo io = new FakeIo();
        StreamLink link = new StreamLink(new IniModel(), io);
        link.connect();
        io.open = false; // a USB read error can clear its open flag before cleanup
        link.disconnect();
        assertTrue("Disconnected driver must release resources",io.closed);
    }

    @Test public void usbSignatureRetriesWhenTheBoardIsSilentDuringStartup() throws Exception {
        byte[] query = SerialProtocol.frame(new byte[] {'Q'});
        FakeIo io = new FakeIo() {
            @Override public int write(byte[] command) {
                int written = super.write(command);
                assertArrayEquals(query, command);
                if (sent.size() == 2 * query.length) {
                    reply = SerialProtocol.frame(new byte[] {0, 't', 'e', 's', 't'});
                }
                return written;
            }
        };
        StreamLink link = new StreamLink(new IniModel(), io, true);
        try {
            link.connect();
            assertEquals("test", link.signature());
            assertEquals(2 * query.length, io.sent.size());
        } finally {
            link.disconnect();
        }
    }

    @Test public void connectionAndReconnectLoadMapCellsAndAxisFromControllerPages() throws Exception {
        IniModel ini = IniModel.load(Path.of("src/main/assets/speeduino.ini"), null);
        Ecu ecu = new Ecu(ini);
        Transport transport = new Transport(ecu);
        try {
            transport.connect(new StreamLink(ini, mapController(ini, 73, 42)));
            assertEquals(73, ecu.get("veTable", 200), 0);
            assertEquals(4200, ecu.get("rpmBins", 5), 0);
            assertFalse(ecu.isDirty());

            transport.disconnect();
            transport.connect(new StreamLink(ini, mapController(ini, 86, 48)));
            assertEquals(86, ecu.get("veTable", 200), 0);
            assertEquals(4800, ecu.get("rpmBins", 5), 0);
            assertFalse(ecu.isDirty());
        } finally {
            transport.disconnect();
        }
    }

    private static FakeIo mapController(IniModel ini, int fuelCell, int rpmBin) {
        byte[][] pages = Arrays.stream(ini.pageSize).mapToObj(size -> new byte[size]).toArray(byte[][]::new);
        pages[1][200] = (byte) fuelCell;
        pages[1][261] = (byte) rpmBin;
        return new FakeIo() {
            @Override public int write(byte[] frame) {
                byte[] data = switch (frame[2]) {
                    case 'Q' -> ini.signature.getBytes(StandardCharsets.US_ASCII);
                    case 'p' -> {
                        int page = frame[4] & 255;
                        int offset = (frame[5] & 255) | ((frame[6] & 255) << 8);
                        int length = (frame[7] & 255) | ((frame[8] & 255) << 8);
                        yield Arrays.copyOfRange(pages[page - 1], offset, offset + length);
                    }
                    case 'r' -> new byte[ini.ochBlockSize];
                    default -> throw new AssertionError("Unexpected command: " + (char) frame[2]);
                };
                byte[] payload = new byte[data.length + 1];
                System.arraycopy(data, 0, payload, 1, data.length);
                reply = SerialProtocol.frame(payload);
                position = 0;
                return frame.length;
            }
        };
    }
    private static class FakeIo implements SerialIo {
        boolean open, closed;
        byte[] reply = new byte[0];
        int position;
        ByteArrayOutputStream sent = new ByteArrayOutputStream();
        public String description() { return "fragmented serial"; }
        public void open() { open=true; }
        public boolean isOpen() { return open; }
        public int read(byte[] out,int count) { if(position>=reply.length)return 0; out[0]=reply[position++];return 1; }
        public int write(byte[] in) { sent.write(in,0,in.length);return in.length; }
        public void close() { closed=true;open=false; }
    }
}
