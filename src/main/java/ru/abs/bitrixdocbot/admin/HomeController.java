package ru.abs.bitrixdocbot.admin;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {

    @GetMapping({"/", "/admin", "/admin/"})
    public String home() {
        return "redirect:/admin/index.html";
    }
}