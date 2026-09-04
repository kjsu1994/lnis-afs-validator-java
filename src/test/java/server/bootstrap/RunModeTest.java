package server.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class RunModeTest {
  @Test
  void separatesModeFromSpringArguments() {
    var selection = RunMode.select(new String[] {"sender", "--lnis.agent.id=sender-a"});

    assertEquals(RunMode.SENDER, selection.mode());
    assertArrayEquals(new String[] {"--lnis.agent.id=sender-a"}, selection.springArguments());
  }

  @Test
  void rejectsMissingOrUnknownMode() {
    assertThrows(IllegalArgumentException.class, () -> RunMode.select(new String[0]));
    assertThrows(IllegalArgumentException.class, () -> RunMode.select(new String[] {"unknown"}));
  }
}
