package sh.pcx.packmerger.upload;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PublicIpDetectorTest {

    @Test
    void acceptsPublicIpLiterals() {
        assertEquals("203.0.113.9", PublicIpDetector.parse("203.0.113.9\n"));
        assertEquals("2001:db8::5", PublicIpDetector.parse(" 2001:db8::5 "));
    }

    @Test
    void rejectsNonRoutableAndGarbage() {
        assertNull(PublicIpDetector.parse("192.168.0.1"));
        assertNull(PublicIpDetector.parse("127.0.0.1"));
        assertNull(PublicIpDetector.parse("<html>blocked</html>"));
        assertNull(PublicIpDetector.parse("example.com"));
        assertNull(PublicIpDetector.parse("300.1.1.1"));
        assertNull(PublicIpDetector.parse(""));
        assertNull(PublicIpDetector.parse(null));
    }
}
