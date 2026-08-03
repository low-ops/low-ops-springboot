package com.example.springbootapp.users;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@Controller
public class PageController {

    @GetMapping("/")
    public String home() {
        return "users/home";
    }

    @GetMapping({"/users/{userId}", "/users/{userId}/"})
    public String detail(@PathVariable long userId, Model model) {
        model.addAttribute("userId", userId);
        return "users/detail";
    }
}
