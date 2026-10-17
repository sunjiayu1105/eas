import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.io.StringReader;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CheckProperties {
    public static void main(String[] args) throws Exception {
        String text = new String(Files.readAllBytes(Paths.get(args[0])), StandardCharsets.UTF_8);
        Properties properties = new Properties();
        Matcher blocks = Pattern.compile("(?s)~~~properties\\r?\\n(.*?)\\r?\\n~~~").matcher(text);
        while (blocks.find()) {
            properties.load(new StringReader(blocks.group(1)));
        }
        String filterText = properties.getProperty("canal.instance.filter.regex");
        String hashConfig = properties.getProperty("canal.mq.partitionHash");
        String hashText = hashConfig.substring(0, hashConfig.lastIndexOf(':'));
        Pattern filter = Pattern.compile(filterText);
        Pattern hash = Pattern.compile(hashText);
        for (int suffix = 0; suffix <= 17; suffix++) {
            String table = String.format("eas.consume_record_%02d", suffix);
            boolean expected = suffix >= 1 && suffix <= 16;
            if (filter.matcher(table).matches() != expected || hash.matcher(table).matches() != expected) {
                throw new AssertionError(table);
            }
        }
        String[] excluded = {"other.consume_record_01", "easXconsume_record_01", "eas.consume_record_010"};
        for (String table : excluded) {
            if (filter.matcher(table).matches() || hash.matcher(table).matches()) {
                throw new AssertionError(table);
            }
        }
        System.out.println("Java 8 Properties and Pattern: all 16 tables match; 00/17 and unrelated names excluded.");
    }
}
