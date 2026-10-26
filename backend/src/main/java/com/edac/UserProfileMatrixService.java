package com.edac;

import org.springframework.stereotype.Service;

@Service
public class UserProfileMatrixService {
    private final UserProfileMatrixSource source;
    public UserProfileMatrixService(UserProfileMatrixSource source){this.source=source;}
    private static String user(String code){
        if(code==null||code.isBlank()||code.trim().length()>256||code.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("USER_CODE must contain 1 to 256 characters without control characters.");
        return code.trim();
    }
    public UserProfileMatrixSource.GridPage matrix(long connectionId,String userCode,int page,int size){return source.matrix(connectionId,user(userCode),page,size);}
    public UserProfileMatrixSource.GridPage details(long connectionId,String userCode,String type,String code,int page,int size){return source.details(connectionId,user(userCode),type,code,page,size);}
    public UserProfileMatrixSource.GridPage children(long connectionId,String userCode,String type,String code,String item,int page,int size){return source.children(connectionId,user(userCode),type,code,item,page,size);}
}
