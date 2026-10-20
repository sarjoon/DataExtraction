package com.edac;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.ArrayList;
import javax.imageio.ImageIO;

final class RuleImageRenderer {
    private RuleImageRenderer() {}
    static byte[] render(String rule) throws IOException {
        // Larger rules remain fully available as text, without an oversized bitmap.
        if(rule.isBlank() || rule.length()>20000)return null;
        var lines=new ArrayList<String>();
        for(String line:rule.replace("\r","").replace("\t","    ").split("\n",-1)) {
            if(line.isEmpty())lines.add("");
            for(int start=0;start<line.length();start+=100)lines.add(line.substring(start,Math.min(line.length(),start+100)));
        }
        if(lines.size()>100)return null;
        var image=new BufferedImage(840,Math.max(40,24+18*lines.size()),BufferedImage.TYPE_INT_RGB);
        var graphics=image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);graphics.fillRect(0,0,image.getWidth(),image.getHeight());
            graphics.setColor(new Color(30,40,55));graphics.setFont(new Font(Font.MONOSPACED,Font.PLAIN,13));
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int y=25;for(String line:lines){graphics.drawString(line,12,y);y+=18;}
        }finally{graphics.dispose();}
        try(var output=new ByteArrayOutputStream()){ImageIO.write(image,"png",output);return output.toByteArray();}
    }
}
