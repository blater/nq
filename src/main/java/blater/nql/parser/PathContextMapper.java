package blater.nql.parser;

import blater.nql.core.parser.HiQLParser;
import blater.nql.domain.HierarchyPath;

final class PathContextMapper {
  private PathContextMapper() {
  }

  static HierarchyPath toHierarchyPath(HiQLParser.PathContext ctx) {
    return HierarchyPath.fromDottedPath(toDottedPath(ctx));
  }

  static String toSlashPath(HiQLParser.PathContext ctx) {
    return "/" + toSeparatedPath(ctx, "/");
  }

  static String toDottedPath(HiQLParser.PathContext ctx) {
    return toSeparatedPath(ctx, ".");
  }

  private static String toSeparatedPath(HiQLParser.PathContext ctx, String separator) {
    StringBuilder path = new StringBuilder(ctx.name().getText());
    for (HiQLParser.PathSegmentContext segment : ctx.pathSegment()) {
      path.append(separator);
      if (segment.AT() != null) {
        path.append("@");
      }
      path.append(segment.name().getText());
    }
    return path.toString();
  }
}
