package server.central.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** AFS·DTN 화면과 기존 북마크 URL의 HTTP 상태 및 문서를 그대로 유지한다. */
@Controller
public class WebPageController {
  @GetMapping("/")
  String root() {
    return "redirect:/lnis/afstest/sender";
  }

  @GetMapping({"/lnis/afstest/sender", "/lnis/test/sender"})
  String sender() {
    return "forward:/sender.html";
  }

  @GetMapping({"/lnis/afstest/receiver", "/lnis/test/receiver"})
  String receiver() {
    return "forward:/receiver.html";
  }

  @GetMapping("/lnis/dtntest/sender")
  String dtn() {
    return "forward:/dtn-sender.html";
  }
}
