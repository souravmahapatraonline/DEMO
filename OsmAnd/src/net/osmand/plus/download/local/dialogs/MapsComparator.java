package net.osmand.plus.download.local.dialogs;

import androidx.annotation.NonNull;

import net.osmand.Collator;
import net.osmand.OsmAndCollator;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.download.local.BaseLocalItem;
import net.osmand.plus.download.local.LocalItem;
import net.osmand.plus.settings.enums.MapsSortMode;

import java.util.Comparator;

public class MapsComparator implements Comparator<BaseLocalItem> {

	public final OsmandApplication app;
	public final MapsSortMode sortMode;
	public final Collator collator = OsmAndCollator.primaryCollator();

	public MapsComparator(@NonNull OsmandApplication app, @NonNull MapsSortMode sortMode) {
		this.app = app;
		this.sortMode = sortMode;
	}

	@Override
	public int compare(BaseLocalItem item1, BaseLocalItem item2) {
		switch (sortMode) {
			case NAME_ASCENDING:
			case COUNTRY_NAME_ASCENDING:
				return compareItemNames(item1, item2);
			case NAME_DESCENDING:
			case COUNTRY_NAME_DESCENDING:
				return -compareItemNames(item1, item2);
			case DATE_ASCENDING:
				return -Long.compare(item1.getLastModified(), item2.getLastModified());
			case DATE_DESCENDING:
				return Long.compare(item1.getLastModified(), item2.getLastModified());
			case SIZE_DESCENDING:
				return -Long.compare(item1.getLocalItemSize(), item2.getLocalItemSize());
			case SIZE_ASCENDING:
				return Long.compare(item1.getLocalItemSize(), item2.getLocalItemSize());
		}
		return 0;
	}

	private int compareItemNames(@NonNull BaseLocalItem item1, @NonNull BaseLocalItem item2) {
		return compareNames(item1.getName(app).toString(), item2.getName(app).toString());
	}

	private int compareNames(@NonNull String name1, @NonNull String name2) {
		return collator.compare(name1, name2);
	}
}
