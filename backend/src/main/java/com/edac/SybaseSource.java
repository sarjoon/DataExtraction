package com.edac;
import static com.edac.Models.*;
import java.sql.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class SybaseSource {
    private final SybaseDriverLoader driver;
    public SybaseSource(SybaseDriverLoader driver){this.driver=driver;}
    public Connection open(ConnectionProfile p,String password)throws SQLException{return driver.open(p,password);}
    public TestResult test(ConnectionProfile p,String password){try(var c=open(p,password);var s=c.createStatement()){s.setQueryTimeout(30);s.executeQuery("SELECT 1").close();return new TestResult(true,"Sybase connection successful.",discover(c,p));}catch(SQLException e){throw failure(e);}}
    public List<TableName> discover(ConnectionProfile p,String password){try(var c=open(p,password)){return discover(c,p);}catch(SQLException e){throw failure(e);}}
    private List<TableName> discover(Connection c,ConnectionProfile p)throws SQLException{
        var result=new ArrayList<TableName>();try(var s=c.prepareStatement("SELECT o.name FROM sysobjects o JOIN sysusers u ON o.uid=u.uid WHERE u.name=? AND o.type IN ('U','V') ORDER BY o.name")){
            s.setString(1,p.schema());s.setQueryTimeout(30);try(var rows=s.executeQuery()){while(rows.next()){result.add(new TableName(p.schema(),rows.getString(1)));if(result.size()>10000)throw new IllegalArgumentException("More than 10,000 Sybase tables/views. Select a narrower owner.");}}
        }return result;
    }
    public List<ColumnDetail> columns(ConnectionProfile p,String password,TableName table){try(var c=open(p,password)){
        var result=new ArrayList<ColumnDetail>();var m=c.getMetaData();try(var r=m.getColumns(c.getCatalog(),pattern(m,table.schema()),pattern(m,table.name()),"%")){while(r.next()){
            if(!r.getString("TABLE_NAME").equals(table.name())||!r.getString("TABLE_SCHEM").equals(table.schema()))continue;
            result.add(new ColumnDetail(r.getInt("ORDINAL_POSITION"),r.getString("COLUMN_NAME"),r.getString("TYPE_NAME"),r.getInt("CHAR_OCTET_LENGTH"),r.getInt("COLUMN_SIZE"),r.getInt("COLUMN_SIZE"),r.getInt("DECIMAL_DIGITS"),r.getInt("NULLABLE")!=DatabaseMetaData.columnNoNulls,true));
        }}if(result.isEmpty())throw new SourceTableUnavailableException("No accessible Sybase columns for the selected table.");return result;
    }catch(SQLException e){throw failure(e);}}
    static String pattern(DatabaseMetaData m,String value)throws SQLException{String escape=m.getSearchStringEscape();if(escape==null||escape.isEmpty())return value;return value.replace(escape,escape+escape).replace("_",escape+"_").replace("%",escape+"%");}
    private IllegalArgumentException failure(SQLException e){return new IllegalArgumentException("Sybase connection or metadata query failed (error "+e.getErrorCode()+"). Check database, owner, permissions and driver configuration.");}
}
