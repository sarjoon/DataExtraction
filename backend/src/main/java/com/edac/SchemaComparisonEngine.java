package com.edac;
import java.util.*;

public final class SchemaComparisonEngine {
    private SchemaComparisonEngine(){}
    public static Map<String,List<Map<String,Object>>> compare(SchemaMetadata.Inventory before,SchemaMetadata.Inventory after,boolean normalize,boolean rowCounts){
        var result=new LinkedHashMap<String,List<Map<String,Object>>>();
        for(String section:List.of("objectCounts","objectDifferences","rowCounts","structureSummary","structureDifferences"))result.put(section,new ArrayList<>());
        for(String type:SchemaMetadata.TYPES){
            var a=names(before.objects().get(type));var b=names(after.objects().get(type));
            boolean unsupported=before.unsupported().contains(type)||after.unsupported().contains(type);
            result.get("objectCounts").add(row("objectType",type,"beforeCount",before.unsupported().contains(type)?null:a.size(),"afterCount",after.unsupported().contains(type)?null:b.size(),"difference",unsupported?null:b.size()-a.size(),"status",unsupported?"Not comparable":a.keySet().equals(b.keySet())?"Match":a.size()==b.size()?"Names differ":"Count mismatch"));
            if(!unsupported)for(String key:union(a.keySet(),b.keySet()))if(!a.containsKey(key)||!b.containsKey(key))result.get("objectDifferences").add(row("objectType",type,"objectName",a.getOrDefault(key,b.get(key)),"before",a.containsKey(key)?"Exists":"Missing","after",b.containsKey(key)?"Exists":"Missing","status",a.containsKey(key)?"Only Before":"Only After"));
        }
        var left=namedTables(before.tables());var right=namedTables(after.tables());
        for(String key:union(left.keySet(),right.keySet())){
            var a=left.get(key);var b=right.get(key);String name=a!=null?a.name():b.name();int start=result.get("structureDifferences").size();
            if(rowCounts)result.get("rowCounts").add(row("tableName",name,"beforeCount",a==null?null:a.rowCount(),"afterCount",b==null?null:b.rowCount(),"difference",a==null||b==null?null:b.rowCount()-a.rowCount(),"status",a==null?"Only After":b==null?"Only Before":Objects.equals(a.rowCount(),b.rowCount())?"Same count":"Count mismatch"));
            boolean incomplete=false;
            if(a==null||b==null)difference(result,name,"","Table",a==null?null:"Exists",b==null?null:"Exists");
            else{
                var ca=columns(a.columns());var cb=columns(b.columns());
                for(String column:union(ca.keySet(),cb.keySet())){
                    var x=ca.get(column);var y=cb.get(column);String columnName=x!=null?x.name():y.name();
                    if(x==null||y==null){difference(result,name,columnName,"Column",x==null?null:definition(x),y==null?null:definition(y));continue;}
                    if(!Objects.equals(normalize?x.logicalType():x.nativeType(),normalize?y.logicalType():y.nativeType()))difference(result,name,columnName,"Data type",x.nativeType(),y.nativeType());
                    for(String property:union(x.properties().keySet(),y.properties().keySet())){
                        String av=x.properties().get(property),bv=y.properties().get(property);
                        if("Not reported".equals(av)||"Not reported".equals(bv)||"Unknown".equals(av)||"Unknown".equals(bv)){incomplete=true;continue;}
                        if(!Objects.equals(property.equals("Default")?canonical(av):av,property.equals("Default")?canonical(bv):bv))difference(result,name,columnName,property,av,bv);
                    }
                }
                compareDefinitions(result,name,"Constraint",a.constraints(),b.constraints());compareDefinitions(result,name,"Index",a.indexes(),b.indexes());
                incomplete|=a.indexes().values().stream().anyMatch(v->v.contains("not reported"))||b.indexes().values().stream().anyMatch(v->v.contains("not reported"))||a.constraints().values().stream().anyMatch(v->v.contains("Not reported"))||b.constraints().values().stream().anyMatch(v->v.contains("Not reported"));
            }
            int changes=result.get("structureDifferences").size()-start;
            result.get("structureSummary").add(row("tableName",name,"beforeColumns",a==null?null:a.columns().size(),"afterColumns",b==null?null:b.columns().size(),"differenceCount",changes,"status",a==null?"Only After":b==null?"Only Before":changes>0?"Structure differs":incomplete?"Review required":"Same structure","coverage",incomplete?"Some driver metadata was not reported":"Compared"));
        }
        return result;
    }
    private static String definition(SchemaMetadata.Column c){return c.nativeType()+" "+c.properties();}
    private static void compareDefinitions(Map<String,List<Map<String,Object>>> result,String table,String kind,Map<String,String> a,Map<String,String> b){
        var left=normalizedKeys(a);var right=normalizedKeys(b);
        for(String name:union(left.keySet(),right.keySet()))if(!Objects.equals(canonical(left.get(name)),canonical(right.get(name))))difference(result,table,name,kind,left.get(name),right.get(name));
    }
    private static Map<String,String> normalizedKeys(Map<String,String> values){var result=new LinkedHashMap<String,String>();values.forEach((k,v)->{if(result.putIfAbsent(k.toUpperCase(Locale.ROOT),v)!=null)throw new IllegalArgumentException("Ambiguous metadata names differing only by case.");});return result;}
    private static void difference(Map<String,List<Map<String,Object>>> result,String table,String object,String property,String a,String b){result.get("structureDifferences").add(row("tableName",table,"columnOrObject",object,"property",property,"before",a,"after",b,"status",a==null?"Added":b==null?"Removed":"Changed"));}
    private static Map<String,String> names(Set<String> values){var result=new TreeMap<String,String>();for(String value:values)if(result.putIfAbsent(value.toUpperCase(Locale.ROOT),value)!=null)throw new IllegalArgumentException("Schema has object names differing only by case. These cannot be matched safely.");return result;}
    private static Map<String,SchemaMetadata.Table> namedTables(Map<String,SchemaMetadata.Table> values){var result=new TreeMap<String,SchemaMetadata.Table>();values.forEach((k,v)->{if(result.putIfAbsent(k.toUpperCase(Locale.ROOT),v)!=null)throw new IllegalArgumentException("Ambiguous table names differing only by case.");});return result;}
    private static Map<String,SchemaMetadata.Column> columns(List<SchemaMetadata.Column> values){var result=new LinkedHashMap<String,SchemaMetadata.Column>();for(var c:values)if(result.putIfAbsent(c.name().toUpperCase(Locale.ROOT),c)!=null)throw new IllegalArgumentException("Ambiguous column names differing only by case.");return result;}
    private static Set<String> union(Set<String> a,Set<String> b){var result=new TreeSet<String>(a);result.addAll(b);return result;}
    static String canonical(String value){
        if(value==null)return null;String s=value.trim();if(s.equalsIgnoreCase("NULL"))return "";
        var result=new StringBuilder();boolean quoted=false,space=false;
        for(int i=0;i<s.length();i++){char c=s.charAt(i);if(c=='\''){if(space&&result.length()>0)result.append(' ');space=false;result.append(c);if(quoted&&i+1<s.length()&&s.charAt(i+1)=='\''){result.append(s.charAt(++i));continue;}quoted=!quoted;}
            else if(!quoted&&Character.isWhitespace(c))space=true;
            else{if(space&&result.length()>0)result.append(' ');space=false;result.append(quoted?c:Character.toUpperCase(c));}}
        return result.toString();
    }
    static Map<String,Object> row(Object... entries){var result=new LinkedHashMap<String,Object>();for(int i=0;i<entries.length;i+=2)result.put(entries[i].toString(),entries[i+1]);return result;}
}
