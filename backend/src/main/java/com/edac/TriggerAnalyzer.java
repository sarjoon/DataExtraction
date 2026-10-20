package com.edac;

import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class TriggerAnalyzer {
    public record TriggerField(String attributeName,String completeCallTrigger,String dropDownNature,String fullRule) {
        public TriggerField(String attributeName,String completeCallTrigger){this(attributeName,completeCallTrigger,"","");}
    }
    private static final String IDENTIFIER="[A-Za-z_][A-Za-z0-9_]*(?:\\s*\\.\\s*[A-Za-z_][A-Za-z0-9_]*)*";
    // Consume literals and comments as whole tokens; only identifiers become candidate fields.
    private static final Pattern SOURCE_TOKEN=Pattern.compile(
        "\"(?:\\\\.|\"\"|[^\"\\\\])*\"|'(?:\\\\.|''|[^'\\\\])*'|\\{[^}]*\\}"
        +"|\\b\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?\\b|(?<field>"+IDENTIFIER+")");
    private static final Set<String> CONSTANTS=Set.of("IF","THEN","ELSE","END","CASE","WHEN","AND","OR","NOT","NULL","TRUE","FALSE","IS","IN","LIKE","BETWEEN");
    private final OracleConnectionFactory connections;
    public TriggerAnalyzer(OracleConnectionFactory connections){this.connections=connections;}

    public List<TriggerField> analyze(Models.ConnectionProfile profile,String password,String screen) {
        String schema=RegistryService.identifier(profile.schema());
        // Fetch script fragments once, retaining the original nature priority and rank order.
        String sql="SELECT da.sqlname_c, sd.nature_e, sd.definition_c FROM "+schema+".script_definition sd "
            +"JOIN "+schema+".dict_attribute da ON da.dict_id=sd.attribute_dict_id "
            +"WHERE sd.object_id IN (SELECT dict_id FROM "+schema+".dict_screen WHERE name=?) "
            +"ORDER BY da.sqlname_c, sd.nature_e, sd.rank_n";
        try(var connection=connections.open(profile,password)) {
            try(var exists=connection.prepareStatement("SELECT dict_id FROM "+schema+".dict_screen WHERE name=?")) {
                exists.setString(1,screen);exists.setQueryTimeout(30);
                try(var rows=exists.executeQuery()) {
                    if(!rows.next())throw new IllegalArgumentException("Screen not found: "+screen);
                }
            }
            try(var statement=connection.prepareStatement(sql)) {
                statement.setString(1,screen);statement.setQueryTimeout(30);statement.setMaxRows(50001);
                Map<String,Map<Integer,StringBuilder>> definitions=new LinkedHashMap<>();int count=0;
                try(var rows=statement.executeQuery()) {
                    while(rows.next()) {
                        if(++count>50000)throw new IllegalArgumentException("Screen exceeds 50,000 script fragments: "+screen);
                        String attribute=rows.getString(1);if(attribute==null)continue;
                        var byNature=definitions.computeIfAbsent(attribute,key->new HashMap<>());
                        int nature=rows.getInt(2);if(rows.wasNull())continue;
                        var text=byNature.computeIfAbsent(nature,key->new StringBuilder());
                        String fragment=rows.getString(3);
                        if(fragment!=null) {
                            if(text.length()+fragment.length()>5_000_000)throw new IllegalArgumentException("Script definition is too large for attribute: "+attribute);
                            text.append(fragment).append('\n');
                        }
                    }
                }
                List<TriggerField> result=new ArrayList<>();
                definitions.forEach((attribute,byNature)->{
                    int nature=byNature.containsKey(10)?10:byNature.containsKey(2)?2:1;
                    var definition=byNature.get(nature);
                    if(definition!=null && !definition.isEmpty())result.add(describe(attribute,definition.toString()));
                });
                return result;
            }
        }catch(SQLException e){throw new IllegalArgumentException("Trigger analysis failed for screen "+screen+" (database error "+e.getErrorCode()+"). Check the Oracle connection and dictionary permissions.");}
    }
    static String extract(String definition) {
        return extract(definition,null);
    }
    static String extract(String definition,String attribute) {
        var matcher=SOURCE_TOKEN.matcher(definition);
        Map<String,String> fields=new LinkedHashMap<>();
        while(matcher.find()) {
            String field=matcher.group("field");if(field==null)continue;
            field=field.replaceAll("\\s+","");
            int next=matcher.end();while(next<definition.length() && Character.isWhitespace(definition.charAt(next)))next++;
            int previous=matcher.start()-1;while(previous>=0 && Character.isWhitespace(definition.charAt(previous)))previous--;
            if(previous>=0 && definition.charAt(previous)=='.')continue; // e.g. GET_OBJECT(...).id
            if(next<definition.length() && definition.charAt(next)=='(' && !field.contains("."))continue;
            field=field.split("\\.",2)[0];
            if(CONSTANTS.contains(field.toUpperCase(Locale.ROOT)))continue;
            fields.putIfAbsent(field.toLowerCase(Locale.ROOT),field);
        }
        return String.join("\n",fields.values());
    }
    static TriggerField describe(String attribute,String definition) {
        boolean dropdown=false;var matcher=SOURCE_TOKEN.matcher(definition);
        while(matcher.find()) {
            if(!"ENUM_LIST".equalsIgnoreCase(matcher.group("field")))continue;
            int next=matcher.end();while(next<definition.length() && Character.isWhitespace(definition.charAt(next)))next++;
            if(next<definition.length() && definition.charAt(next)=='('){dropdown=true;break;}
        }
        return new TriggerField(attribute,extract(definition),dropdown?definition:"",definition);
    }
}
