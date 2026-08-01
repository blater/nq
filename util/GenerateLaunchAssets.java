import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

public final class GenerateLaunchAssets {
  private static final Color BACKGROUND = Color.decode("#0b1020");
  private static final Color HEADER = Color.decode("#182036");
  private static final Color WHITE = Color.decode("#f8fafc");
  private static final Color MUTED = Color.decode("#94a3b8");
  private static final Color BLUE = Color.decode("#65d1ff");
  private static final Color PINK = Color.decode("#f9a8d4");
  private static final Color PURPLE = Color.decode("#c084fc");
  private static final Color YELLOW = Color.decode("#fbbf24");
  private static final Color GREEN = Color.decode("#86efac");
  private static final Font MONO = new Font(Font.MONOSPACED, Font.PLAIN, 21);
  private static final Font MONO_SMALL = new Font(Font.MONOSPACED, Font.PLAIN, 18);

  private GenerateLaunchAssets() {
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 1) {
      throw new IllegalArgumentException("Usage: GenerateLaunchAssets <output-directory>");
    }
    Path output = Path.of(args[0]);
    Files.createDirectories(output);
    writeTerminalFrame(output.resolve("terminal-demo-frame-1.png"), GenerateLaunchAssets::frameInput);
    writeTerminalFrame(output.resolve("terminal-demo-frame-2.png"), GenerateLaunchAssets::frameQuery);
    writeTerminalFrame(output.resolve("terminal-demo-frame-3.png"), GenerateLaunchAssets::frameCommand);
    writeTerminalFrame(output.resolve("terminal-demo-frame-4.png"), GenerateLaunchAssets::frameOutput);
    writeSocialPreview(output.resolve("nq-social-preview.png"));
  }

  private static void writeTerminalFrame(Path output, FramePainter painter) throws Exception {
    BufferedImage image = new BufferedImage(1000, 560, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = image.createGraphics();
    configure(graphics);
    graphics.setColor(BACKGROUND);
    graphics.fill(new RoundRectangle2D.Double(0, 0, 1000, 560, 22, 22));
    graphics.setColor(HEADER);
    graphics.fill(new RoundRectangle2D.Double(0, 0, 1000, 58, 22, 22));
    graphics.fillRect(0, 38, 1000, 20);
    dot(graphics, 31, Color.decode("#ff5f57"));
    dot(graphics, 57, Color.decode("#febc2e"));
    dot(graphics, 83, Color.decode("#28c840"));
    graphics.setFont(MONO_SMALL);
    centered(graphics, "NQ — SQL for nested data", 500, 36, Color.decode("#cbd5e1"));
    painter.paint(graphics);
    graphics.dispose();
    ImageIO.write(image, "png", output.toFile());
  }

  private static void frameInput(Graphics2D graphics) {
    text(graphics, "$", 34, 103, BLUE);
    text(graphics, "cat identity-customers.json", 58, 103, WHITE);
    text(graphics, "{", 34, 151, MUTED);
    text(graphics, "  \"identity_data\": {", 58, 187, PINK);
    text(graphics, "    \"customer\": [ ... ],", 82, 223, PINK);
    text(graphics, "    \"address\": [ ... ],", 82, 259, PINK);
    text(graphics, "    \"kyc\": [ ... ]", 82, 295, PINK);
    text(graphics, "  }", 58, 331, MUTED);
    text(graphics, "}", 34, 367, MUTED);
    text(graphics, "Nested collections become related SQL tables.", 34, 441, Color.decode("#cbd5e1"));
    cursor(graphics, 34, 481);
  }

  private static void frameQuery(Graphics2D graphics) {
    text(graphics, "$", 34, 96, BLUE);
    text(graphics, "cat identity-country-counts.nq", 58, 96, WHITE);
    text(graphics, "select", 34, 140, PURPLE);
    text(graphics, "  a.country_code into {result.region.country},", 58, 175, WHITE);
    text(graphics, "  count(distinct c.id) into {result.region.customerCount}", 58, 210, WHITE);
    text(graphics, "from customer c", 34, 245, PURPLE);
    text(graphics, "join address a on a.customer_id = c.id", 34, 280, WHITE);
    text(graphics, "join kyc k on k.customer_id = c.id", 34, 315, WHITE);
    text(graphics, "group by a.country_code", 34, 350, WHITE);
    text(graphics, "structure {result.region} key (country_key);", 34, 385, YELLOW);
    text(graphics, "Familiar SQL controls the query; paths control the output.", 34, 454, Color.decode("#cbd5e1"));
    cursor(graphics, 34, 493);
  }

  private static void frameCommand(Graphics2D graphics) {
    text(graphics, "$", 34, 110, BLUE);
    text(graphics, "nq identity-country-counts.nq \\", 58, 110, WHITE);
    text(graphics, "  identity-customers.json", 58, 147, WHITE);
    text(graphics, "Discovering customer, address, and kyc relations...", 34, 226, MUTED);
    text(graphics, "Running SQL and mapping result.region...", 34, 274, MUTED);
    cursor(graphics, 34, 326);
  }

  private static void frameOutput(Graphics2D graphics) {
    text(graphics, "$ nq identity-country-counts.nq identity-customers.json", 34, 100, WHITE);
    text(graphics, "{", 34, 160, MUTED);
    text(graphics, "  \"result\": {", 58, 198, PINK);
    text(graphics, "    \"region\": [", 82, 236, PINK);
    text(graphics, "      { \"country\": \"GB\", \"customerCount\": \"2\" },", 106, 274, GREEN);
    text(graphics, "      { \"country\": \"US\", \"customerCount\": \"4\" }", 106, 312, GREEN);
    text(graphics, "    ]", 82, 350, MUTED);
    text(graphics, "  }", 58, 388, MUTED);
    text(graphics, "}", 34, 426, MUTED);
    text(graphics, "One query. New hierarchy. No application code.", 34, 490, GREEN);
    cursor(graphics, 34, 514);
  }

  private static void writeSocialPreview(Path output) throws Exception {
    BufferedImage image = new BufferedImage(1280, 640, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = image.createGraphics();
    configure(graphics);
    graphics.setPaint(new java.awt.GradientPaint(0, 0, BACKGROUND, 1280, 640, Color.decode("#182a4d")));
    graphics.fillRect(0, 0, 1280, 640);
    graphics.setColor(Color.decode("#0f172a"));
    graphics.fill(new RoundRectangle2D.Double(86, 82, 1108, 476, 28, 28));
    graphics.setColor(Color.decode("#334155"));
    graphics.setStroke(new BasicStroke(3));
    graphics.draw(new RoundRectangle2D.Double(86, 82, 1108, 476, 28, 28));
    dot(graphics, 130, Color.decode("#ff5f57"));
    dot(graphics, 162, Color.decode("#febc2e"));
    dot(graphics, 194, Color.decode("#28c840"));
    graphics.setFont(new Font(Font.MONOSPACED, Font.BOLD, 104));
    text(graphics, "NQ", 126, 254, BLUE);
    graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 62));
    text(graphics, "SQL for nested data", 126, 341, WHITE);
    graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 30));
    text(graphics, "Query and reshape JSON · YAML · XML · CSV · JSONL · Parquet", 126, 411, Color.decode("#cbd5e1"));
    graphics.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 28));
    text(graphics, "files ⇄ SQL ⇄ hierarchical output ⇄ databases", 126, 468, GREEN);
    graphics.dispose();
    ImageIO.write(image, "png", output.toFile());
  }

  private static void configure(Graphics2D graphics) {
    graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    graphics.setFont(MONO);
  }

  private static void dot(Graphics2D graphics, int centerX, Color color) {
    graphics.setColor(color);
    graphics.fillOval(centerX - 8, 21, 16, 16);
  }

  private static void text(Graphics2D graphics, String value, int x, int y, Color color) {
    graphics.setColor(color);
    graphics.drawString(value, x, y);
  }

  private static void centered(Graphics2D graphics, String value, int centerX, int y, Color color) {
    FontMetrics metrics = graphics.getFontMetrics();
    text(graphics, value, centerX - metrics.stringWidth(value) / 2, y, color);
  }

  private static void cursor(Graphics2D graphics, int x, int y) {
    graphics.setColor(BLUE);
    graphics.fillRect(x, y, 12, 25);
  }

  @FunctionalInterface
  private interface FramePainter {
    void paint(Graphics2D graphics);
  }
}
