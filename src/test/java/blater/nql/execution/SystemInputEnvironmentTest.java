package blater.nql.execution;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemInputEnvironmentTest {
  @Test
  void exposesInjectedInputAndTerminalDisposition() {
    InputStream input = new ByteArrayInputStream(new byte[] {1});
    var environment = new SystemInputEnvironment(
        input, () -> StdinDisposition.TERMINAL);

    assertSame(input, environment.stdin());
    assertEquals(StdinDisposition.TERMINAL, environment.stdinDisposition());
  }

  @Test
  void dispositionDetectionDoesNotInspectTheInputStream() {
    InputStream input = new InputStream() {
      @Override
      public int read() {
        throw new AssertionError("terminal detection read stdin");
      }

      @Override
      public int available() {
        throw new AssertionError("terminal detection inspected available bytes");
      }
    };
    var environment = new SystemInputEnvironment(
        input, () -> StdinDisposition.REDIRECTED);

    assertEquals(StdinDisposition.REDIRECTED, environment.stdinDisposition());
  }

  @Test
  void delayedRedirectedInputIsNotMistakenForImmediatelyAvailableInput() throws IOException {
    InputStream delayed = new InputStream() {
      private boolean consumed;

      @Override
      public int read() {
        if (consumed) {
          return -1;
        }
        consumed = true;
        return 'x';
      }

      @Override
      public int available() {
        return 0;
      }
    };
    var environment = new SystemInputEnvironment(
        delayed, () -> StdinDisposition.REDIRECTED);

    assertFalse(environment.hasImmediatelyAvailableInput());
    assertEquals(StdinDisposition.REDIRECTED, environment.stdinDisposition());
    assertEquals('x', environment.stdin().read());
  }

  @Test
  void immediateInputHintReportsBufferedBytes() {
    var environment = new SystemInputEnvironment(
        new ByteArrayInputStream(new byte[] {1}),
        () -> StdinDisposition.REDIRECTED);

    assertTrue(environment.hasImmediatelyAvailableInput());
  }

  @Test
  void immediateInputHintTreatsIoFailureAsNoWarningEvidence() {
    InputStream broken = new InputStream() {
      @Override
      public int read() {
        return -1;
      }

      @Override
      public int available() throws IOException {
        throw new IOException("unavailable");
      }
    };
    var environment = new SystemInputEnvironment(
        broken, () -> StdinDisposition.UNKNOWN);

    assertFalse(environment.hasImmediatelyAvailableInput());
  }
}
