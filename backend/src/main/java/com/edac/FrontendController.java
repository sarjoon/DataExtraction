package com.edac;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class FrontendController {
    @GetMapping({"/overview", "/connections", "/tables", "/mapping", "/generation", "/triggers", "/snapshot", "/snapshot/tables", "/snapshot/batches", "/snapshot/configuration", "/snapshot/capture", "/snapshot/history", "/snapshot/report/{id}", "/snapshot/report/{id}/{table}"})
    public String frontend() {
        return "forward:/index.html";
    }
    @GetMapping({"/snapshot/schema","/snapshot/schema/history","/snapshot/schema/report/{id}"})
    public String schema(){return "forward:/index.html";}
    @GetMapping({"/reports","/reports/user-profile-matrix"})
    public String reports(){return "forward:/index.html";}
}
