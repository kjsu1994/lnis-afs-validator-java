package server.central.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:lnis-web;DB_CLOSE_DELAY=-1",
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "lnis.storage.data-directory=${java.io.tmpdir}/lnis-web-tests",
      "lnis.storage.cleanup-delay=PT24H"
    })
@ActiveProfiles("server")
@AutoConfigureMockMvc
class WebPageControllerTest {
  @Autowired private MockMvc mvc;

  @Test
  void servesNewAndLegacyPagesWithoutNginx() throws Exception {
    mvc.perform(get("/"))
        .andExpect(status().isFound())
        .andExpect(redirectedUrl("/lnis/afstest/sender"));
    mvc.perform(get("/lnis/afstest/sender"))
        .andExpect(status().isOk())
        .andExpect(forwardedUrl("/sender.html"));
    mvc.perform(get("/sender.html"))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Sender 시험 제어")));
    mvc.perform(get("/lnis/test/sender")).andExpect(status().isOk());
    mvc.perform(get("/lnis/afstest/receiver")).andExpect(status().isOk());
    mvc.perform(get("/lnis/test/receiver")).andExpect(status().isOk());
    mvc.perform(get("/lnis/dtntest/sender")).andExpect(status().isOk());
    mvc.perform(get("/lnis/assets/api.js")).andExpect(status().isOk());
    mvc.perform(get("/lnis/api/v1/discovery"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.service").value("lnis-server"));
  }
}
