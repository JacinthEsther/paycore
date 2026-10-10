package com.fintechplatform.paycore.demo;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class DemoDocuments {

    private DemoDocuments() {
    }

    static byte[] pdf(String title, List<String> lines) {

        StringBuilder text = new StringBuilder("BT /F1 20 Tf 60 760 Td (" + escape(title) + ") Tj ET\n");
        int y = 720;
        for (String line : lines) {
            text.append("BT /F1 12 Tf 60 ").append(y).append(" Td (").append(escape(line)).append(") Tj ET\n");
            y -= 22;
        }
        text.append("BT /F1 10 Tf 60 80 Td (SAMPLE DOCUMENT - PayCore Developer Preview - not a real ID) Tj ET\n");

        List<String> objects = List.of(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R "
                        + "/Resources << /Font << /F1 5 0 R >> >> >>",
                "<< /Length " + text.length() + " >>\nstream\n" + text + "endstream",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"
        );

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<Integer> offsets = new ArrayList<>();
        write(out, "%PDF-1.4\n");
        for (int i = 0; i < objects.size(); i++) {
            offsets.add(out.size());
            write(out, (i + 1) + " 0 obj\n" + objects.get(i) + "\nendobj\n");
        }
        int xref = out.size();
        write(out, "xref\n0 " + (objects.size() + 1) + "\n0000000000 65535 f \n");
        offsets.forEach(offset -> write(out, "%010d 00000 n \n".formatted(offset)));
        write(out, "trailer\n<< /Size " + (objects.size() + 1) + " /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n");
        return out.toByteArray();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)");
    }

    private static void write(ByteArrayOutputStream out, String value) {
        out.writeBytes(value.getBytes(StandardCharsets.ISO_8859_1));
    }
}
