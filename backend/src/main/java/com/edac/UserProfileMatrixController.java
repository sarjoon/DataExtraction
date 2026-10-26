package com.edac;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/reports/user-profile-matrix/connections/{connectionId}")
public class UserProfileMatrixController {
    private final UserProfileMatrixService service;
    public UserProfileMatrixController(UserProfileMatrixService service){this.service=service;}
    @GetMapping("/matrix") public UserProfileMatrixSource.GridPage matrix(@PathVariable long connectionId,@RequestParam String userCode,
        @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="100") int size){return service.matrix(connectionId,userCode,page,size);}
    @GetMapping("/profiles/{type}") public UserProfileMatrixSource.GridPage details(@PathVariable long connectionId,@RequestParam String userCode,@PathVariable String type,
        @RequestParam String profileCode,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="100") int size){return service.details(connectionId,userCode,type,profileCode,page,size);}
    @GetMapping("/profiles/{type}/items") public UserProfileMatrixSource.GridPage children(@PathVariable long connectionId,@RequestParam String userCode,@PathVariable String type,
        @RequestParam String profileCode,@RequestParam String item,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="100") int size){return service.children(connectionId,userCode,type,profileCode,item,page,size);}
}
