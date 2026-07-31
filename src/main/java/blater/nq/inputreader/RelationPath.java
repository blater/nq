package blater.nq.inputreader;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Canonical, typed source location of a relation. */
public record RelationPath(List<Segment> segments) implements Comparable<RelationPath> {
  public RelationPath {
    segments = List.copyOf(segments);
  }

  public static RelationPath root() {
    return new RelationPath(List.of());
  }

  public RelationPath member(String name) {
    return append(new Member(name));
  }

  public RelationPath each() {
    return append(EachRecord.INSTANCE);
  }

  public RelationPath position(int index) {
    if (index < 0) throw new IllegalArgumentException("Relation path position cannot be negative.");
    return append(new Position(index));
  }

  public String leafName() {
    if (segments.isEmpty()) return "";
    Segment segment = segments.getLast();
    return segment instanceof Member member ? member.name() : "";
  }

  public String display() {
    if (segments.isEmpty()) return "/";
    StringBuilder path = new StringBuilder();
    for (Segment segment : segments) {
      path.append('/');
      if (segment instanceof Member member) path.append(escape(member.name()));
      else if (segment instanceof Position position) path.append(position.index());
      else path.append('*');
    }
    return path.toString();
  }

  @Override
  public int compareTo(RelationPath other) {
    return display().compareTo(other.display());
  }

  @Override
  public String toString() {
    return display();
  }

  private RelationPath append(Segment segment) {
    List<Segment> result = new ArrayList<>(segments);
    result.add(Objects.requireNonNull(segment));
    return new RelationPath(result);
  }

  static String escape(String value) {
    return value.replace("~", "~0").replace("/", "~1");
  }

  static String unescape(String value) {
    StringBuilder result = new StringBuilder();
    for (int index = 0; index < value.length(); index++) {
      char ch = value.charAt(index);
      if (ch != '~') {
        result.append(ch);
        continue;
      }
      if (index + 1 >= value.length()) {
        throw new IllegalArgumentException("Malformed relation path escape.");
      }
      char escaped = value.charAt(++index);
      if (escaped == '0') result.append('~');
      else if (escaped == '1') result.append('/');
      else throw new IllegalArgumentException("Malformed relation path escape [~" + escaped + "].");
    }
    return result.toString();
  }

  public sealed interface Segment permits Member, EachRecord, Position { }

  public record Member(String name) implements Segment {
    public Member {
      Objects.requireNonNull(name);
    }
  }

  public enum EachRecord implements Segment { INSTANCE }

  public record Position(int index) implements Segment {
    public Position {
      if (index < 0) throw new IllegalArgumentException("Relation path position cannot be negative.");
    }
  }
}
