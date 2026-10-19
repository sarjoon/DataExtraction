package com.edac;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class FrontendController {
    @GetMapping({"/overview", "/connections", "/tables", "/mapping", "/generation", "/triggers"})
    public String frontend() {
        return "forward:/index.html";
    }
}
