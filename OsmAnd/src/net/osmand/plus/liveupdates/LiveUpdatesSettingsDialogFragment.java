package net.osmand.plus.liveupdates;

import android.app.AlarmManager;
import android.app.Dialog;
import android.app.PendingIntent;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.app.DialogFragment;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.SwitchCompat;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.Spinner;
import android.widget.TextView;

import net.osmand.PlatformUtil;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.OsmandSettings;
import net.osmand.plus.R;
import net.osmand.plus.activities.LocalIndexInfo;
import net.osmand.plus.download.AbstractDownloadActivity;
import net.osmand.plus.download.DownloadActivity;
import net.osmand.plus.resources.IncrementalChangesManager;
import net.osmand.util.Algorithms;

import org.apache.commons.logging.Log;

import java.io.File;

import static net.osmand.plus.liveupdates.LiveUpdatesHelper.DEFAULT_LAST_CHECK;
import static net.osmand.plus.liveupdates.LiveUpdatesHelper.TimeOfDay;
import static net.osmand.plus.liveupdates.LiveUpdatesHelper.UpdateFrequency;
import static net.osmand.plus.liveupdates.LiveUpdatesHelper.formatDateTime;
import static net.osmand.plus.liveupdates.LiveUpdatesHelper.getNameToDisplay;
import static net.osmand.plus.liveupdates.LiveUpdatesHelper.getPendingIntent;
import static net.osmand.plus.liveupdates.LiveUpdatesHelper.preferenceDownloadViaWiFi;
import static net.osmand.plus.liveupdates.LiveUpdatesHelper.preferenceForLocalIndex;
import static net.osmand.plus.liveupdates.LiveUpdatesHelper.preferenceLastCheck;
import static net.osmand.plus.liveupdates.LiveUpdatesHelper.preferenceTimeOfDayToUpdate;
import static net.osmand.plus.liveupdates.LiveUpdatesHelper.preferenceUpdateFrequency;
import static net.osmand.plus.liveupdates.LiveUpdatesHelper.setAlarmForPendingIntent;

public class LiveUpdatesSettingsDialogFragment extends DialogFragment {
	private static final Log LOG = PlatformUtil.getLog(LiveUpdatesAlarmReceiver.class);
	private static final String LOCAL_INDEX = "local_index";

	@NonNull
	@Override
	public Dialog onCreateDialog(Bundle savedInstanceState) {
		AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
		final LocalIndexInfo localIndexInfo = getArguments().getParcelable(LOCAL_INDEX);

		View view = LayoutInflater.from(getActivity())
				.inflate(R.layout.dialog_live_updates_item_settings, null);
		final TextView regionNameTextView = (TextView) view.findViewById(R.id.regionNameTextView);
		final TextView lastMapChangeTextView = (TextView) view.findViewById(R.id.lastMapChangeTextView);
		final TextView lastUpdateTextView = (TextView) view.findViewById(R.id.lastUpdateTextView);
		final SwitchCompat liveUpdatesSwitch = (SwitchCompat) view.findViewById(R.id.liveUpdatesSwitch);
		final CheckBox downloadOverWiFiCheckBox = (CheckBox) view.findViewById(R.id.downloadOverWiFiSwitch);
		final Spinner updateFrequencySpinner = (Spinner) view.findViewById(R.id.updateFrequencySpinner);
		final Spinner updateTimesOfDaySpinner = (Spinner) view.findViewById(R.id.updateTimesOfDaySpinner);
		final View updateTimesOfDayList = view.findViewById(R.id.updateTimesOfDayList);
		final TextView sizeTextView = (TextView) view.findViewById(R.id.sizeTextView);
		final Button removeUpdatesButton = (Button) view.findViewById(R.id.removeUpdatesButton);

		regionNameTextView.setText(getNameToDisplay(localIndexInfo, getMyActivity()));
		final String fileNameWithoutExtension =
				Algorithms.getFileNameWithoutExtension(new File(localIndexInfo.getFileName()));
		final IncrementalChangesManager changesManager = getMyApplication().getResourceManager().getChangesManager();
		final long timestamp = changesManager.getTimestamp(fileNameWithoutExtension);
		String lastUpdateDate = formatDateTime(getActivity(), timestamp);
		final long lastCheck = preferenceLastCheck(localIndexInfo, getSettings()).get();
		String lastCheckString = formatDateTime(getActivity(), lastCheck != DEFAULT_LAST_CHECK
				? lastCheck : timestamp);
		lastMapChangeTextView.setText(getString(R.string.last_map_change, lastUpdateDate));
		lastUpdateTextView.setText(getString(R.string.last_update, lastCheckString));

		final OsmandSettings.CommonPreference<Boolean> liveUpdatePreference =
				preferenceForLocalIndex(localIndexInfo, getSettings());
		final OsmandSettings.CommonPreference<Boolean> downloadViaWiFiPreference =
				preferenceDownloadViaWiFi(localIndexInfo, getSettings());
		final OsmandSettings.CommonPreference<Integer> updateFrequencyPreference =
				preferenceUpdateFrequency(localIndexInfo, getSettings());
		final OsmandSettings.CommonPreference<Integer> timeOfDayPreference =
				preferenceTimeOfDayToUpdate(localIndexInfo, getSettings());

		downloadOverWiFiCheckBox.setChecked(!liveUpdatePreference.get() || downloadViaWiFiPreference.get());

		updateSize(fileNameWithoutExtension, changesManager, sizeTextView);

		updateTimesOfDaySpinner.setAdapter(new ArrayAdapter<String>(getActivity(),
				R.layout.action_spinner_item,
				getResources().getStringArray(R.array.update_times_of_day)));

		updateFrequencySpinner.setAdapter(new ArrayAdapter<String>(getActivity(),
				R.layout.action_spinner_item,
				getResources().getStringArray(R.array.update_frequencies_array)));
		updateFrequencySpinner.setSelection(updateFrequencyPreference.get());
		updateFrequencySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
			@Override
			public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
				UpdateFrequency updateFrequency = UpdateFrequency.values()[position];
				switch (updateFrequency) {
					case HOURLY:
						updateTimesOfDayList.setVisibility(View.GONE);
						break;
					case DAILY:
					case WEEKLY:
						updateTimesOfDayList.setVisibility(View.VISIBLE);
						break;
				}
			}

			@Override
			public void onNothingSelected(AdapterView<?> parent) {

			}
		});
		removeUpdatesButton.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				changesManager.deleteUpdates(fileNameWithoutExtension);
				getLiveUpdatesFragment().notifyLiveUpdatesChanged();
				updateSize(fileNameWithoutExtension, changesManager, sizeTextView);
			}
		});

		builder.setView(view)
				.setPositiveButton(R.string.shared_string_ok, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						if (liveUpdatePreference.get() != liveUpdatesSwitch.isChecked()) {
							liveUpdatePreference.set(liveUpdatesSwitch.isChecked());
							if (liveUpdatesSwitch.isChecked()) {
								runLiveUpdate(localIndexInfo);
							}
						}
						downloadViaWiFiPreference.set(downloadOverWiFiCheckBox.isChecked());

						final int updateFrequencyInt = updateFrequencySpinner.getSelectedItemPosition();
						updateFrequencyPreference.set(updateFrequencyInt);

						AlarmManager alarmMgr = (AlarmManager) getActivity()
								.getSystemService(Context.ALARM_SERVICE);
						PendingIntent alarmIntent = getPendingIntent(getActivity(), localIndexInfo);

						final int timeOfDayInt = updateTimesOfDaySpinner.getSelectedItemPosition();
						timeOfDayPreference.set(timeOfDayInt);

						if (liveUpdatesSwitch.isChecked() && getSettings().IS_LIVE_UPDATES_ON.get()) {
							UpdateFrequency updateFrequency = UpdateFrequency.values()[updateFrequencyInt];
							TimeOfDay timeOfDayToUpdate = TimeOfDay.values()[timeOfDayInt];
							setAlarmForPendingIntent(alarmIntent, alarmMgr, updateFrequency, timeOfDayToUpdate);
						} else {
							alarmMgr.cancel(alarmIntent);
						}
						getLiveUpdatesFragment().notifyLiveUpdatesChanged();
					}
				})
				.setNegativeButton(R.string.shared_string_cancel, null)
				.setNeutralButton(R.string.update_now, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						runLiveUpdate(localIndexInfo);
						updateSize(fileNameWithoutExtension, changesManager, sizeTextView);
					}
				});
		return builder.create();
	}

	void runLiveUpdate(final LocalIndexInfo info) {
		final String fnExt = Algorithms.getFileNameWithoutExtension(new File(info.getFileName()));
		new PerformLiveUpdateAsyncTask(getActivity(), info).execute(new String[]{fnExt});
	}

	private void updateSize(String fileNameWithoutExtension,
							IncrementalChangesManager changesManager,
							TextView sizeTextView) {
		String size;
		long updatesSize = changesManager.getUpdatesSize(fileNameWithoutExtension);
		updatesSize /= (1 << 10);
		if (updatesSize > 100) {
			size = DownloadActivity.formatMb.format(new Object[]{(float) updatesSize / (1 << 10)});
		} else {
			size = updatesSize + " KB";
		}
		sizeTextView.setText(getString(R.string.updates_size_pattern, size));
	}

	private LiveUpdatesFragment getLiveUpdatesFragment() {
		return (LiveUpdatesFragment) getParentFragment();
	}

	private OsmandSettings getSettings() {
		return getMyApplication().getSettings();
	}

	private OsmandApplication getMyApplication() {
		return getMyActivity().getMyApplication();
	}

	private AbstractDownloadActivity getMyActivity() {
		return (AbstractDownloadActivity) this.getActivity();
	}

	public static LiveUpdatesSettingsDialogFragment createInstance(LocalIndexInfo localIndexInfo) {
		LiveUpdatesSettingsDialogFragment fragment = new LiveUpdatesSettingsDialogFragment();
		Bundle args = new Bundle();
		args.putParcelable(LOCAL_INDEX, localIndexInfo);
		fragment.setArguments(args);
		return fragment;
	}
}
