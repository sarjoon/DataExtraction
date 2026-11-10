package com.edac.extraction;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import com.edac.extraction.Definitions.*;

/** One output stream per table. No table-sized collections or byte arrays. */
public final class DelimitedOutput implements AutoCloseable {
    private final Path path;
    private final String delimiter;
    private final BufferedWriter writer;
    private final MessageDigest digest;
    private final Table table;
    private long records;
    private BigDecimal sum=BigDecimal.ZERO;
    private static final DateTimeFormatter TIMESTAMP=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    public DelimitedOutput(Path path,String delimiter,String system,String country,LocalDate date,Table table) throws Exception {
        if(delimiter==null || delimiter.isEmpty() || delimiter.contains("\n") || delimiter.contains("\r") || delimiter.contains("\0") || delimiter.contains("\""))
            throw new IllegalArgumentException("Delimiter must be nonempty and exclude quotes, CR, LF and NUL.");
        this.path=path;this.delimiter=delimiter;this.table=table;
        Files.createDirectories(path.getParent());digest=MessageDigest.getInstance("SHA-256");
        writer=new BufferedWriter(new OutputStreamWriter(new DigestOutputStream(Files.newOutputStream(path),digest),StandardCharsets.UTF_8),65536);
        writer.write("H");for(String value:List.of(system,country,date.format(DateTimeFormatter.BASIC_ISO_DATE),LocalDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")))){writer.write(delimiter);field(value,false);}writer.write('\n');writer.flush();
    }
    private void field(String value,boolean alwaysQuote) throws IOException {
        if(value==null){writer.write("\\N");return;}
        value=value.replace('\r',' ').replace('\n',' ');
        boolean quote=alwaysQuote || value.contains(delimiter) || value.contains("\"") || value.equals("\\N");
        if(quote)writer.write('"');
        for(int i=0;i<value.length();i++){char c=value.charAt(i);if(c=='"')writer.write('"');writer.write(c);}
        if(quote)writer.write('"');
    }
    private void stream(Reader reader) throws IOException {
        if(reader==null){field(null,false);return;}
        writer.write('"');char[] buffer=new char[8192];
        for(int count;(count=reader.read(buffer))!=-1;)for(int i=0;i<count;i++){char c=buffer[i];if(c=='\r'||c=='\n')c=' ';if(c=='"')writer.write('"');writer.write(c);}
        writer.write('"');
    }
    private void hex(InputStream stream) throws IOException {
        if(stream==null){field(null,false);return;}
        writer.write('"');byte[] buffer=new byte[8192];char[] alphabet="0123456789ABCDEF".toCharArray();
        for(int count;(count=stream.read(buffer))!=-1;)for(int i=0;i<count;i++){int b=buffer[i]&255;writer.write(alphabet[b>>>4]);writer.write(alphabet[b&15]);}
        writer.write('"');
    }
    public void jdbcRow(ResultSet row) throws Exception {
        writer.write("D");var utc=Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        for(int i=0;i<table.columns().size();i++) {
            var column=table.columns().get(i);int position=i+1;writer.write(delimiter);
            String type=column.type().toUpperCase(Locale.ROOT);
            if(type.equals("BLOB")){try(var input=row.getBinaryStream(position)){hex(input);}}
            else if(type.equals("CLOB")||type.equals("NCLOB")){try(var input=row.getCharacterStream(position)){stream(input);}}
            else {
                String value;
                if(column.numeric()){var number=row.getBigDecimal(position);value=number==null?null:number.toPlainString();}
                else if(type.equals("DATE")){var timestamp=row.getTimestamp(position,utc);value=timestamp==null?null:timestamp.toInstant().atOffset(ZoneOffset.UTC).toLocalDate().toString();}
                else if(type.startsWith("TIMESTAMP")){var timestamp=row.getTimestamp(position,utc);value=timestamp==null?null:timestamp.toInstant().atOffset(ZoneOffset.UTC).format(TIMESTAMP);}
                else if(type.equals("RAW")){var bytes=row.getBytes(position);value=bytes==null?null:HexFormat.of().withUpperCase().formatHex(bytes);}
                else value=row.getString(position);
                field(value,false);addSum(column.name(),value);
            }
        }
        completeRow();
    }
    public void sqlclRow(List<String> marked) throws IOException {
        if(marked.size()!=table.columns().size())throw new IOException("SQLcl returned an unexpected number of columns.");
        writer.write("D");
        for(int i=0;i<marked.size();i++) {
            String encoded=marked.get(i),value;
            if(encoded.equals("N"))value=null;
            else if(encoded.startsWith("V"))value=encoded.substring(1);
            else throw new IOException("Invalid SQLcl field marker. Check the SQLcl log and output settings.");
            var column=table.columns().get(i);
            if(column.lob()&&value!=null){String end="~EDAC_LOB_END~";if(!value.endsWith(end))throw new IOException("SQLcl truncated a LOB value. Check LONG settings and the SQLcl log.");value=value.substring(0,value.length()-end.length());}
            if(column.numeric() && value!=null)value=new BigDecimal(value).toPlainString();
            writer.write(delimiter);field(value,column.lob());addSum(column.name(),value);
        }
        completeRow();
    }
    private void addSum(String name,String value){if(name.equals(table.controlSumColumn()) && value!=null)sum=sum.add(new BigDecimal(value));}
    private void completeRow() throws IOException {
        writer.write('\n');records++;
        // The next ResultSet.next() (and any next fetch) occurs after this flush.
        if(records%2000==0)writer.flush();
    }
    /** Chunk files have the same encoding and contain no embedded record newlines. */
    public void appendChunk(Path chunk,Stats stats) throws IOException {
        try(var reader=Files.newBufferedReader(chunk,StandardCharsets.UTF_8)) {
            char[] buffer=new char[65536];boolean start=true,detail=false;
            for(int n;(n=reader.read(buffer))!=-1;) {
                int begin=0;
                for(int i=0;i<n;i++) {
                    if(start){detail=buffer[i]=='D';start=false;begin=i;}
                    if(buffer[i]=='\n'){if(detail)writer.write(buffer,begin,i-begin+1);start=true;begin=i+1;}
                }
                if(!start&&detail&&begin<n)writer.write(buffer,begin,n-begin);
            }
        }
        records+=stats.records();if(!stats.controlSum().isBlank())sum=sum.add(new BigDecimal(stats.controlSum()));writer.flush();
    }
    public Stats finish() throws IOException {
        writer.write("T");writer.write(delimiter);writer.write(Long.toString(records));
        String total=table.controlSumColumn()==null || table.controlSumColumn().isBlank()?"":sum.toPlainString();
        if(!total.isEmpty()){writer.write(delimiter);field(total,false);}writer.write('\n');writer.flush();
        return new Stats(records,total,Files.size(path),HexFormat.of().formatHex(digest.digest()));
    }
    @Override public void close() throws IOException{writer.close();}
}
