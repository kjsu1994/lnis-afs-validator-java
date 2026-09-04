package server.bootstrap;

import java.util.Arrays;
import java.util.Locale;

/** 실행 명령의 첫 인수를 Spring profile과 Web 모드로 변환한다. */
public enum RunMode {
  SERVER,
  SENDER,
  RECEIVER;

  public String profile() {
    return name().toLowerCase(Locale.ROOT);
  }

  public static Selection select(String[] arguments) {
    if (arguments.length == 0 || arguments[0].startsWith("--")) {
      throw new IllegalArgumentException(
          "실행 모드가 필요합니다: java -jar lnis.jar <server|sender|receiver> [Spring 옵션]");
    }
    try {
      RunMode mode = valueOf(arguments[0].toUpperCase(Locale.ROOT));
      return new Selection(mode, Arrays.copyOfRange(arguments, 1, arguments.length));
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(
          "지원하지 않는 실행 모드입니다: " + arguments[0] + " (server, sender, receiver 중 선택)", error);
    }
  }

  public record Selection(RunMode mode, String[] springArguments) {}
}
