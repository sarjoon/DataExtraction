package com.edac;

import static org.assertj.core.api.Assertions.*;
import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class RuleImageRendererTest {
    @Test void rendersReadablePngAndFallsBackForOversizedRules() throws Exception {
        byte[] png=RuleImageRenderer.render("IS_BUSINESSDATE(DATEADD(value_d,YEAR,ud_tenor_term_e),op_currency_id.calendar_id)");
        var image=ImageIO.read(new ByteArrayInputStream(png));
        var directory=java.nio.file.Path.of("../outputs/rule-verification");java.nio.file.Files.createDirectories(directory);
        java.nio.file.Files.write(directory.resolve("rule.png"),png);
        assertThat(image.getWidth()).isEqualTo(840);assertThat(image.getHeight()).isGreaterThanOrEqualTo(40);
        assertThat(RuleImageRenderer.render("line\n".repeat(101))).isNull();
        assertThat(RuleImageRenderer.render("a".repeat(20001))).isNull();
        assertThat(RuleImageRenderer.render("")).isNull();
    }
}
