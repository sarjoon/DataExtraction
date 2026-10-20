package com.edac;

import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class TriggerAnalyzer {
    public record TriggerField(String attributeName,String completeCallTrigger,String dropDownNature,String fullRule,String screenName) {
        public TriggerField(String attributeName,String completeCallTrigger){this(attributeName,completeCallTrigger,"","","");}
        public TriggerField(String attributeName,String completeCallTrigger,String dropDownNature,String fullRule){this(attributeName,completeCallTrigger,dropDownNature,fullRule,"");}
    }
    private static final String IDENTIFIER="[A-Za-z_][A-Za-z0-9_]*(?:\\s*\\.\\s*[A-Za-z_][A-Za-z0-9_]*)*";
    // Consume literals and comments as whole tokens; only identifiers become candidate fields.
    private static final Pattern SOURCE_TOKEN=Pattern.compile(
        "\"(?:\\\\.|\"\"|[^\"\\\\])*\"|'(?:\\\\.|''|[^'\\\\])*'|\\{[^}]*\\}"
        +"|\\\\[nr]|\\b\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?\\b|(?<field>"+IDENTIFIER+")");
    private static final Set<String> CONSTANTS=Set.of("IF","THEN","ELSE","END","CASE","WHEN","AND","OR","NOT","NULL","TRUE","FALSE","IS","IN","LIKE","BETWEEN");
    private final OracleConnectionFactory connections;
    private final ReadableFieldService readable;
    public TriggerAnalyzer(OracleConnectionFactory connections,ReadableFieldService readable){this.connections=connections;this.readable=readable;}

    public List<TriggerField> analyze(Models.ConnectionProfile profile,String password,String screen) {
        String schema=RegistryService.identifier(profile.schema());
        var allowed=readable.names(profile.id());
        if(allowed.isEmpty())throw new IllegalArgumentException("Configure readable Screen Name and Field Name pairs for this connection before generating a report.");
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
                Map<ReadableFieldService.FieldName,Map<Integer,StringBuilder>> definitions=new LinkedHashMap<>();int count=0;
                try(var rows=statement.executeQuery()) {
                    while(rows.next()) {
                        if(++count>50000)throw new IllegalArgumentException("Screen exceeds 50,000 script fragments: "+screen);
                        String attribute=rows.getString(1);if(attribute==null)continue;
                        var key=new ReadableFieldService.FieldName(screen,attribute.toUpperCase(Locale.ROOT));
                        if(!allowed.contains(key))continue;
                        var byNature=definitions.computeIfAbsent(new ReadableFieldService.FieldName(screen,attribute),ignored->new HashMap<>());
                        int nature=rows.getInt(2);if(rows.wasNull())continue;
                        var text=byNature.computeIfAbsent(nature,ignored->new StringBuilder());
                        String fragment=rows.getString(3);
                        if(fragment!=null) {
                            if(text.length()+fragment.length()>5_000_000)throw new IllegalArgumentException("Script definition is too large for attribute: "+attribute);
                            text.append(fragment).append('\n');
                        }
                    }
                }
                List<TriggerField> result=new ArrayList<>();
                definitions.forEach((field,byNature)->{
                    int nature=byNature.containsKey(10)?10:byNature.containsKey(2)?2:1;
                    var definition=byNature.get(nature);
                    if(definition!=null && !definition.isEmpty()) {
                        var resultField=describe(field.fieldName(),definition.toString());
                        result.add(new TriggerField(resultField.attributeName(),resultField.completeCallTrigger(),resultField.dropDownNature(),resultField.fullRule(),field.screenName()));
                    }
                });
                return result;
            }
        }catch(SQLException e){throw new IllegalArgumentException("Trigger analysis failed for screen "+screen+" (database error "+e.getErrorCode()+"). Check the Oracle connection and dictionary permissions.");}
    }
    static String extract(String definition) {
        return extract(definition,null);
    }
    static String extract(String definition,String attribute) {
        String script=singleLine(withoutComments(definition));
        var matcher=SOURCE_TOKEN.matcher(script);
        Map<String,String> fields=new LinkedHashMap<>();
        while(matcher.find()) {
            String field=matcher.group("field");if(field==null)continue;
            field=field.replaceAll("\\s+","");
            int next=matcher.end();while(next<script.length() && Character.isWhitespace(script.charAt(next)))next++;
            // A bare '=' (or ':=') assigns this identifier; comparisons keep their operands.
            if(next<script.length() && script.charAt(next)=='=' && (next+1>=script.length() || script.charAt(next+1)!='='))continue;
            if(next+1<script.length() && script.charAt(next)==':' && script.charAt(next+1)=='=')continue;
            int previous=matcher.start()-1;while(previous>=0 && Character.isWhitespace(script.charAt(previous)))previous--;
            if(previous>=0 && script.charAt(previous)=='.')continue; // e.g. GET_OBJECT(...).id
            if(next<script.length() && script.charAt(next)=='(' && !field.contains("."))continue;
            if(CONSTANTS.contains(field.toUpperCase(Locale.ROOT)) || field.equals(field.toUpperCase(Locale.ROOT)))continue;
            fields.putIfAbsent(field.toLowerCase(Locale.ROOT),field);
        }
        return String.join("\n",fields.values());
    }
    static TriggerField describe(String attribute,String definition) {
        String script=singleLine(withoutComments(definition));
        boolean dropdown=false;var matcher=SOURCE_TOKEN.matcher(script);
        while(matcher.find()) {
            if(!"ENUM_LIST".equalsIgnoreCase(matcher.group("field")))continue;
            int next=matcher.end();while(next<script.length() && Character.isWhitespace(script.charAt(next)))next++;
            if(next<script.length() && script.charAt(next)=='('){dropdown=true;break;}
        }
        return new TriggerField(attribute,extract(definition),dropdown?definition:"",definition);
    }
    private static String singleLine(String rule) {
        return rule.replace("\\r\\n","\n").replace("\\n","\n").replace("\\r","\n").replaceAll("[\\t ]*\\R[\\t ]*","");
    }
    private static String withoutComments(String definition) {
        char[] text=definition.toCharArray();int depth=0;char quote=0;
        for(int i=0;i<text.length;i++) {
            char c=text[i];
            if(depth>0) {
                if(c=='{')depth++;else if(c=='}')depth--;
                if(c!='\n'&&c!='\r')text[i]=' ';
            }else if(quote!=0) {
                if(c=='\\'&&i+1<text.length)i++;
                else if(c==quote){if(i+1<text.length&&text[i+1]==quote)i++;else quote=0;}
            }else if(c=='\''||c=='"')quote=c;
            else if(c=='{'){depth=1;text[i]=' ';}
        }
        return new String(text);
    }
}
