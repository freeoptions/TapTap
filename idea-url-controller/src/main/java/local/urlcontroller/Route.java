package local.urlcontroller;

/** File-local data only: safe for IntelliJ's persistent file index. */
public record Route(String path, String verb, String owner, String method, int offset) {}
