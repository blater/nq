package blater.nq.execution;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlatformTerminalDetectorTest {
  @Test
  void unsupportedOperatingSystemReturnsUnknownWithoutNativeLinkage() {
    assertEquals(
        StdinDisposition.UNKNOWN,
        PlatformTerminalDetector.forOperatingSystem("Plan 9").detect());
  }
}
