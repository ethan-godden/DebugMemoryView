package eclipseview.ui;

import org.eclipse.ui.IMemento;

/** Per-view render settings, persisted via the view memento. */
public final class ViewSettings {

    public int maxHeapObjectsRendered = 200;
    public int maxFieldsPerObjectRendered = 16;
    public int maxArrayElementsRendered = 10;
    public int maxLocalsPerFrameRendered = 24;
    public int maxValueChars = 60;
    public boolean showStatics = true;
    public boolean highlightChanges = true;
    public double sashRatio = 0.42;

    public void save(IMemento memento) {
        memento.putInteger("maxHeapObjects", maxHeapObjectsRendered);
        memento.putInteger("maxFields", maxFieldsPerObjectRendered);
        memento.putInteger("maxArrayElements", maxArrayElementsRendered);
        memento.putInteger("maxLocals", maxLocalsPerFrameRendered);
        memento.putInteger("maxValueChars", maxValueChars);
        memento.putBoolean("showStatics", showStatics);
        memento.putBoolean("highlightChanges", highlightChanges);
        memento.putInteger("sashRatioMillis", (int) Math.round(sashRatio * 1000));
    }

    public void restore(IMemento memento) {
        if (memento == null) {
            return;
        }
        maxHeapObjectsRendered = valueOr(memento.getInteger("maxHeapObjects"), maxHeapObjectsRendered);
        maxFieldsPerObjectRendered = valueOr(memento.getInteger("maxFields"), maxFieldsPerObjectRendered);
        maxArrayElementsRendered = valueOr(memento.getInteger("maxArrayElements"), maxArrayElementsRendered);
        maxLocalsPerFrameRendered = valueOr(memento.getInteger("maxLocals"), maxLocalsPerFrameRendered);
        maxValueChars = valueOr(memento.getInteger("maxValueChars"), maxValueChars);
        showStatics = boolValueOr(memento.getBoolean("showStatics"), showStatics);
        highlightChanges = boolValueOr(memento.getBoolean("highlightChanges"), highlightChanges);
        Integer ratio = memento.getInteger("sashRatioMillis");
        if (ratio != null) {
            sashRatio = Math.clamp(ratio / 1000.0, 0.2, 0.8);
        }
    }

    private static int valueOr(Integer value, int fallback) {
        return value != null ? value : fallback;
    }

    private static boolean boolValueOr(Boolean value, boolean fallback) {
        return value != null ? value : fallback;
    }
}
