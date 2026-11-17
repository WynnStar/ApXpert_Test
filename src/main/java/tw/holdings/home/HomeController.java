package tw.holdings.home;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter DISPLAY_TIME =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss");

    @GetMapping("/")
    public String home(Model model) {
        ZonedDateTime now = ZonedDateTime.now(TAIPEI);
        model.addAttribute("currentTime", now.format(DISPLAY_TIME));
        model.addAttribute("dateTime", now.toOffsetDateTime().toString());
        return "home";
    }
}
