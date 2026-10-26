package com.edac;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.sql.DatabaseMetaData;
import org.junit.jupiter.api.Test;

class UserProfileMatrixSqlTest {
    @Test void qualifiesAndQuotesEveryTableUsingTheChosenDatabaseIdentifierRules()throws Exception{
        var metadata=mock(DatabaseMetaData.class);
        when(metadata.getIdentifierQuoteString()).thenReturn("\"");when(metadata.storesUpperCaseIdentifiers()).thenReturn(true);
        for(var query:UserProfileMatrixSource.Query.values()){
            String oracle=UserProfileMatrixSource.sql(query,metadata,"ORACLE","APP");
            assertThat(oracle).doesNotContain("$"+"{").contains("\"APP\".");
            String sybase=UserProfileMatrixSource.sql(query,metadata,"SYBASE","company_owner");
            assertThat(sybase).doesNotContain("$"+"{").contains("\"company_owner\".");
        }
        assertThat(UserProfileMatrixSource.sql(UserProfileMatrixSource.Query.REPORT,metadata,"ORACLE","APP")).contains("\"APP\".\"REPORT_PROFILE\"");
        assertThat(UserProfileMatrixSource.sql(UserProfileMatrixSource.Query.REPORT,metadata,"SYBASE","company_owner")).contains("\"company_owner\".\"report_profile\"");
        when(metadata.storesUpperCaseIdentifiers()).thenReturn(false);when(metadata.storesLowerCaseIdentifiers()).thenReturn(true);
        assertThat(UserProfileMatrixSource.sql(UserProfileMatrixSource.Query.REPORT,metadata,"H2","public")).contains("\"public\".\"report_profile\"");
    }
}
