package blater.nq.execution;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Locale;

/**
 * Detects whether file descriptor/handle zero is a terminal without depending
 * on stdout. Native linkage is initialized lazily on first detection.
 */
final class PlatformTerminalDetector {
  private static final int STDIN_FILE_DESCRIPTOR = 0;
  private static final int WINDOWS_STD_INPUT_HANDLE = -10;

  private PlatformTerminalDetector() {
  }

  static TerminalDetector forCurrentPlatform() {
    return forOperatingSystem(System.getProperty("os.name", ""));
  }

  static TerminalDetector forOperatingSystem(String operatingSystem) {
    String osName = operatingSystem.toLowerCase(Locale.ROOT);
    if (osName.startsWith("windows")) {
      return PlatformTerminalDetector::detectWindows;
    }
    if (osName.contains("linux") || osName.contains("mac")
        || osName.contains("darwin") || osName.contains("bsd")
        || osName.contains("sunos")) {
      return PlatformTerminalDetector::detectPosix;
    }
    return () -> StdinDisposition.UNKNOWN;
  }

  private static StdinDisposition detectPosix() {
    try {
      int result = (int) PosixBindings.ISATTY.invokeExact(STDIN_FILE_DESCRIPTOR);
      return result == 1 ? StdinDisposition.TERMINAL : StdinDisposition.REDIRECTED;
    } catch (Throwable ignored) {
      return StdinDisposition.UNKNOWN;
    }
  }

  private static StdinDisposition detectWindows() {
    try {
      MemorySegment handle = (MemorySegment) WindowsBindings.GET_STD_HANDLE
          .invokeExact(WINDOWS_STD_INPUT_HANDLE);
      if (handle.equals(MemorySegment.NULL) || handle.address() == -1L) {
        return StdinDisposition.REDIRECTED;
      }
      try (Arena arena = Arena.ofConfined()) {
        MemorySegment mode = arena.allocate(WindowsBindings.DWORD);
        int result = (int) WindowsBindings.GET_CONSOLE_MODE.invokeExact(handle, mode);
        return result == 0 ? StdinDisposition.REDIRECTED : StdinDisposition.TERMINAL;
      }
    } catch (Throwable ignored) {
      return StdinDisposition.UNKNOWN;
    }
  }

  private static ValueLayout nativeLayout(Linker linker, String name) {
    MemoryLayout layout = linker.canonicalLayouts().get(name);
    if (!(layout instanceof ValueLayout valueLayout)) {
      throw new IllegalStateException("Missing native layout: " + name);
    }
    return valueLayout;
  }

  private static final class PosixBindings {
    private static final Linker LINKER = Linker.nativeLinker();
    private static final ValueLayout C_INT = nativeLayout(LINKER, "int");
    private static final MethodHandle ISATTY = LINKER.downcallHandle(
        LINKER.defaultLookup().find("isatty")
            .orElseThrow(() -> new IllegalStateException("isatty is unavailable")),
        FunctionDescriptor.of(C_INT, C_INT));
  }

  private static final class WindowsBindings {
    private static final Linker LINKER = Linker.nativeLinker();
    private static final ValueLayout C_INT = nativeLayout(LINKER, "int");
    private static final ValueLayout DWORD = nativeLayout(LINKER, "long");
    private static final ValueLayout HANDLE = ValueLayout.ADDRESS;
    private static final MethodHandle GET_STD_HANDLE = LINKER.downcallHandle(
        LINKER.defaultLookup().find("GetStdHandle")
            .orElseThrow(() -> new IllegalStateException("GetStdHandle is unavailable")),
        FunctionDescriptor.of(HANDLE, C_INT));
    private static final MethodHandle GET_CONSOLE_MODE = LINKER.downcallHandle(
        LINKER.defaultLookup().find("GetConsoleMode")
            .orElseThrow(() -> new IllegalStateException("GetConsoleMode is unavailable")),
        FunctionDescriptor.of(C_INT, HANDLE, HANDLE));
  }
}
