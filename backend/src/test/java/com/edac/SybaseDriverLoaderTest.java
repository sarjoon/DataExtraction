package com.edac;
import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.util.jar.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SybaseDriverLoaderTest {
    @TempDir Path directory;
    @ParameterizedTest @ValueSource(strings={"jdbc42","jdbc4"})
    void loadsExternalJdbcJarWithoutCompileTimeSybaseDependency(String driverPackage)throws Exception{
        Path source=directory.resolve("com/sybase/"+driverPackage+"/jdbc/SybDriver.java");Files.createDirectories(source.getParent());
        Files.writeString(source,"""
package com.sybase.jdbc42.jdbc;
public class SybDriver implements java.sql.Driver {
 public java.sql.Connection connect(String url,java.util.Properties p){
  return (java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{java.sql.Connection.class},(proxy,m,args)->{
   if(m.getName().equals("getClientInfo"))return args[0].equals("url")?url:p.getProperty((String)args[0]);
   if(m.getName().equals("createStatement"))return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{java.sql.Statement.class},(s,method,a)->method.getName().equals("execute")?true:null);
   if(m.getName().equals("isClosed"))return false;
   return null;
  });
 }
 public boolean acceptsURL(String u){return u.startsWith("jdbc:sybase:Tds:");}
 public java.sql.DriverPropertyInfo[] getPropertyInfo(String u,java.util.Properties p){return new java.sql.DriverPropertyInfo[0];}
 public int getMajorVersion(){return 1;}public int getMinorVersion(){return 0;}public boolean jdbcCompliant(){return false;}
 public java.util.logging.Logger getParentLogger(){return java.util.logging.Logger.getGlobal();}
}
""".replace("jdbc42",driverPackage));
        assertThat(ToolProvider.getSystemJavaCompiler().run(null,null,null,"-d",directory.toString(),source.toString())).isZero();
        Path jar=directory.resolve("company driver.jar");try(var output=new JarOutputStream(Files.newOutputStream(jar))){output.putNextEntry(new JarEntry("com/sybase/"+driverPackage+"/jdbc/SybDriver.class"));output.write(Files.readAllBytes(source.resolveSibling("SybDriver.class")));output.closeEntry();}
        var profile=new Models.ConnectionProfile(1,"Test","SYBASE","company-host",5000,"ASE","AAA","","test_user","dbo",true,true);
        var loader=new SybaseDriverLoader(jar.toString());try(var c=loader.open(profile,"test-password")){
            assertThat(c.getClientInfo("url")).isEqualTo("jdbc:sybase:Tds:company-host:5000/AAA");assertThat(c.getClientInfo("user")).isEqualTo("test_user");assertThat(c.getClientInfo("password")).isEqualTo("test-password");
        }finally{loader.close();}
    }
    @Test void givesActionableErrorsForMissingOrWrongJar()throws Exception{
        var profile=new Models.ConnectionProfile(1,"Test","SYBASE","host",5000,"ASE","AAA","","u","dbo",true,true);
        assertThatThrownBy(()->new SybaseDriverLoader("").open(profile,"")).hasMessageContaining("before -jar");
        assertThatThrownBy(()->new SybaseDriverLoader(directory.resolve("missing.jar").toString()).open(profile,"")).hasMessageContaining("missing or unreadable");
        Path jar=directory.resolve("empty.jar");try(var output=new JarOutputStream(Files.newOutputStream(jar))){}
        assertThatThrownBy(()->new SybaseDriverLoader(jar.toString()).open(profile,"")).hasMessageContaining("Cannot load");
        assertThatThrownBy(()->SybaseDriverLoader.url("host;bad",5000,"AAA")).hasMessageContaining("valid Sybase");
    }
}
