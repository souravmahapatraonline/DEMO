package net.osmand.router;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.apache.commons.logging.Log;

import net.osmand.binary.BinaryHHRouteReaderAdapter.HHRouteRegion;
import net.osmand.PlatformUtil;
import net.osmand.binary.BinaryMapIndexReader;
import net.osmand.data.LatLon;
import net.osmand.map.OsmandRegions;
import net.osmand.map.WorldRegion;
import net.osmand.util.Algorithms;
import net.osmand.util.MapUtils;

public class MissingMapsCalculator {
	protected static final Log log = PlatformUtil.getLog(MissingMapsCalculator.class);

	public static final double DISTANCE_SPLIT = 50000;
	private OsmandRegions or;
	private BinaryMapIndexReader reader;
	private List<String> lastKeyNames ;

	private static class Point {
		List<String> regions;
		long[] hhEditions; // 0 means routing data present but no HH data, null means no data at all
		TreeSet<Long> editionsUnique;
	}
	
	private static class RegisteredMap {
		BinaryMapIndexReader reader;
		boolean standard;
		long edition;
		String downloadName;
	}

	public MissingMapsCalculator() throws IOException {
		// could be cached
		or = new OsmandRegions();
		reader = or.prepareFile();
	}
	
	public boolean checkIfThereAreMissingMaps(RoutingContext ctx, LatLon start, List<LatLon> targets, boolean checkHHEditions, HHRouteDataStructure.HHNetworkRouteRes res)
			throws IOException {
		long tm = System.nanoTime();
		lastKeyNames = new ArrayList<String>();
		List<Point> pointsToCheck = new ArrayList<>();
		String profile = ctx.getRouter().getProfile().getBaseProfile(); // use base profile
		Map<String, RegisteredMap> knownMaps = new TreeMap<>();

		List<LatLon> testObj = new ArrayList<>();
		if (res != null) {
			for (RouteSegmentResult r : res.detailed) {
				LatLon l = new LatLon(MapUtils.get31LatitudeY(r.getObject().getPoint31YTile(0)),
										MapUtils.get31LongitudeX(r.getObject().getPoint31XTile(0)));
				testObj.add(l);
			}
		}

		if (testObj.size() == 0) {
			/*for (BinaryMapIndexReader r : ctx.map.keySet()) {
				RegisteredMap rmap = new RegisteredMap();
				rmap.downloadName = Algorithms.getRegionName(r.getFile().getName());
				rmap.reader = r;
				rmap.standard = or.getRegionDataByDownloadName(rmap.downloadName) != null;
				knownMaps.put(rmap.downloadName, rmap);
				for (HHRouteRegion rt : r.getHHRoutingIndexes()) {
					if (rt.profile.equals(profile)) {
						rmap.edition = rt.edition;
					}
				}
			}*/
			LatLon end = null;
			for (int i = 0; i < targets.size(); i++) {
				LatLon s = i == 0 ? start : targets.get(i - 1);
				end = targets.get(i);
				split(ctx, knownMaps, pointsToCheck, s, end);
			}
			if (end != null) {
				addPoint(ctx, knownMaps, pointsToCheck, end);
			}
		} else {
			splitTest(ctx, knownMaps, pointsToCheck, testObj);
		}
		Set<String> mapsToDownload = new TreeSet<String>();
		Set<String> mapsToUpdate = new TreeSet<String>();
		Set<Long> presentTimestamps = null;
		for (Point p : pointsToCheck) {
			if (p.hhEditions == null) {
				if (p.regions.size() > 0) {
					mapsToDownload.add(p.regions.get(0));
				}
			} else if (checkHHEditions) {
				if (presentTimestamps == null) {
					presentTimestamps = new TreeSet<Long>(p.editionsUnique);
				} else if (!presentTimestamps.isEmpty()) {
					presentTimestamps.retainAll(p.editionsUnique);
				}
			}
		}
		// maps to update
		if (presentTimestamps != null && presentTimestamps.isEmpty()) {
			long max = 0;
			for (Point p : pointsToCheck) {
				if (p.editionsUnique != null) {
					max = Math.max(p.editionsUnique.last(), max);
				}
			}
			for (Point p : pointsToCheck) {
				String region = null;
				for (int i = 0; p.hhEditions != null && i < p.hhEditions.length; i++) {
					if (p.hhEditions[i] > 0) {
						if (p.hhEditions[i] != max) {
							region = p.regions.get(i);
						} else {
							region = null;
							break;
						}
					}
				}
				if (region != null) {
					mapsToUpdate.add(region);
				}
			}
		}
		if (mapsToDownload.isEmpty() && mapsToUpdate.isEmpty()) {
			return false;
		}
		ctx.calculationProgress.requestMapsToUpdate = true;
		ctx.calculationProgress.missingMaps = convert(mapsToDownload);
		ctx.calculationProgress.mapsToUpdate = convert(mapsToUpdate);

		log.info(String.format("Check missing maps %d points %.2f sec", pointsToCheck.size(),
				(System.nanoTime() - tm) / 1e9));
		return true;
	}

	private List<WorldRegion> convert(Set<String> mapsToDownload) {
		if (mapsToDownload.isEmpty()) {
			return null;
		}
		List<WorldRegion> l = new ArrayList<WorldRegion>();
		for (String m : mapsToDownload) {
			WorldRegion wr = or.getRegionDataByDownloadName(m);
			if (wr != null) {
				l.add(wr);
			}
		}
		return l;
	}

	private void addPoint(RoutingContext ctx, Map<String, RegisteredMap> knownMaps, List<Point> pointsToCheck, LatLon s) throws IOException {
		List<String> regions = new ArrayList<String>();
		or.getRegionsToDownload(s.getLatitude(), s.getLongitude(), regions);
		Collections.sort(regions, new Comparator<String>() {

			@Override
			public int compare(String o1, String o2) {
				return -Integer.compare(o1.length(), o2.length());
			}
		});
		if (pointsToCheck.size() == 0 || !regions.equals(lastKeyNames)) {
			Point pnt = new Point();
			lastKeyNames = regions;
			pnt.regions = new ArrayList<String>(regions);
			boolean hasHHEdition = addMapEditions(knownMaps, pnt);
			if (!hasHHEdition) {
				pnt.hhEditions = null; // recreate
				// check non-standard maps
				int x31 = MapUtils.get31TileNumberX(s.getLongitude());
				int y31 = MapUtils.get31TileNumberY(s.getLatitude());
				for (RegisteredMap r : knownMaps.values()) {
					if (!r.standard) {
						if (r.reader.containsRouteData() && r.reader.containsActualRouteData(x31, y31, null)) {
							pnt.regions.add(0, r.downloadName);
						}
					}
				}
				addMapEditions(knownMaps, pnt);
			}
			pointsToCheck.add(pnt);
		}
	}
	
	private boolean addMapEditions(Map<String, RegisteredMap> knownMaps, Point pnt) {
		boolean hhEditionPresent = false;
		for (int i = 0; i < pnt.regions.size(); i++) {
			String regionName = pnt.regions.get(i);
			if (knownMaps.containsKey(regionName)) {
				if (pnt.hhEditions == null) {
					pnt.hhEditions = new long[pnt.regions.size()];
					pnt.editionsUnique = new TreeSet<Long>();
				}
				pnt.hhEditions[i] = knownMaps.get(regionName).edition;
				hhEditionPresent |= pnt.hhEditions[i] > 0;
				pnt.editionsUnique.add(pnt.hhEditions[i]);
			}
		}
		return hhEditionPresent;
	}

	private void split(RoutingContext ctx, Map<String, RegisteredMap> knownMaps, List<Point> pointsToCheck, LatLon s, LatLon e) throws IOException {
		if (MapUtils.getDistance(s, e) < DISTANCE_SPLIT) {
			addPoint(ctx, knownMaps, pointsToCheck, s);
			// pointsToCheck.add(e); // add only start end is separate
		} else {
			LatLon mid = MapUtils.calculateMidPoint(s, e);
			split(ctx, knownMaps, pointsToCheck, s, mid);
			split(ctx, knownMaps, pointsToCheck, mid, e);
		}
	}

	private void splitTest(RoutingContext ctx, Map<String, RegisteredMap> knownMaps, List<Point> pointsToCheck, List<LatLon> testObj) throws IOException {
		LatLon last = testObj.get(0);
		addPoint(ctx, knownMaps, pointsToCheck, last);
		for (LatLon l : testObj) {
			if (MapUtils.getDistance(last, l) >= DISTANCE_SPLIT) {
				addPoint(ctx, knownMaps, pointsToCheck, l);
				last = l;
			}
		}
		addPoint(ctx, knownMaps, pointsToCheck, testObj.get(testObj.size() - 1));
	}

	public void close() throws IOException {
		reader.close();
	}

	public String getErrorMessage(RoutingContext ctx) {
		String msg = "";
		if (ctx.calculationProgress.mapsToUpdate != null) {
			msg = ctx.calculationProgress.mapsToUpdate + " need to be updated";
		}
		if (ctx.calculationProgress.missingMaps != null) {
			if (msg.length() > 0) {
				msg += " and ";
			}
			msg = ctx.calculationProgress.missingMaps + " need to be downloaded";
		}
		msg = "To calculate the route maps " + msg;
		return msg;
	}

}
