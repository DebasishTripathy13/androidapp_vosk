package com.example.docscriptai;

import java.io.File;

/** Represents a downloadable / importable LiteRT (.task) LLM. */
public class AIModel {
    public final String name;
    public final String displayName;
    public final String description;
    public final String modelId;
    public final String fileName;
    public final long sizeInBytes;
    public final String version;

    /** Non-null only for files found on-device but not yet imported into app storage. */
    public File sourceFile;

    public AIModel(String name, String displayName, String description,
                   String modelId, String fileName, long sizeInBytes, String version) {
        this.name = name;
        this.displayName = displayName;
        this.description = description;
        this.modelId = modelId;
        this.fileName = fileName;
        this.sizeInBytes = sizeInBytes;
        this.version = version;
    }

    public String getFormattedSize() {
        if (sizeInBytes <= 0) return "—";
        if (sizeInBytes < 1024 * 1024) return String.format("%.1f KB", sizeInBytes / 1024.0);
        if (sizeInBytes < 1024L * 1024 * 1024) return String.format("%.1f MB", sizeInBytes / (1024.0 * 1024));
        return String.format("%.2f GB", sizeInBytes / (1024.0 * 1024 * 1024));
    }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AIModel)) return false;
        return name.equals(((AIModel) o).name);
    }

    @Override public int hashCode() { return name.hashCode(); }

    @Override public String toString() { return displayName + " (" + getFormattedSize() + ")"; }
}
