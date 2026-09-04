package server.agent.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/** Windows 배포 DLL이 기존 JNA ABI로 실제 로드되는지 확인한다. */
@EnabledOnOs(OS.WINDOWS)
class NativeAfsCodecTest {
  @Test
  void loadsExternalWindowsDllWithAbiOne() {
    try (var codec = NativeAfsCodec.load(Path.of("native/bin/win-x64"))) {
      assertEquals(1, codec.abiVersion());
    }
  }
}
