package com.github.topi314.lavasrc.mirror;

import com.github.topi314.lavasrc.ExtendedAudioTrack;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.io.PersistentHttpStream;
import com.sedmelluq.discord.lavaplayer.tools.io.SeekableInputStream;
import com.sedmelluq.discord.lavaplayer.track.*;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public abstract class MirroringAudioTrack extends ExtendedAudioTrack {

	private static final Logger log = LoggerFactory.getLogger(MirroringAudioTrack.class);
	private static final int LOAD_TIMEOUT_SECONDS = 30;

	protected final MirroringAudioSourceManager sourceManager;

	public MirroringAudioTrack(AudioTrackInfo trackInfo, String albumName, String albumUrl, String artistUrl, String artistArtworkUrl, String previewUrl, boolean isPreview, MirroringAudioSourceManager sourceManager) {
		super(trackInfo, albumName, albumUrl, artistUrl, artistArtworkUrl, previewUrl, isPreview);
		this.sourceManager = sourceManager;
	}

	protected abstract InternalAudioTrack createAudioTrack(AudioTrackInfo trackInfo, SeekableInputStream inputStream);

	@Override
	public void process(LocalAudioTrackExecutor executor) throws Exception {
		if (this.isPreview) {
			processPreview(executor);
			return;
		}

		AudioItem resolvedTrack = null;
		try {
			resolvedTrack = this.sourceManager.getResolver().apply(this);
		} catch (Exception e) {
			log.error("Failed to resolve mirror for track: {} by {}", 
				this.trackInfo.title, this.trackInfo.author, e);
			throw new TrackNotFoundException("Mirror resolution failed: " + e.getMessage());
		}

		if (resolvedTrack == null || resolvedTrack == AudioReference.NO_TRACK) {
			throw new TrackNotFoundException("No mirror found for track: " + this.trackInfo.title);
		}

		InternalAudioTrack playableTrack = extractPlayableTrack(resolvedTrack);
		if (playableTrack == null) {
			throw new TrackNotFoundException("Could not extract playable track from mirror result");
		}

		playableTrack.setUserData(this.getUserData());
		
		log.debug("Playing mirror: {} - {} from {} (original: {} by {})", 
			playableTrack.getInfo().title,
			playableTrack.getInfo().author,
			playableTrack.getSourceManager().getSourceName(),
			this.trackInfo.title,
			this.trackInfo.author
		);

		processDelegate(playableTrack, executor);
	}

	private void processPreview(LocalAudioTrackExecutor executor) throws Exception {
		if (this.previewUrl == null || this.previewUrl.isEmpty()) {
			throw new FriendlyException("No preview URL available", 
				FriendlyException.Severity.COMMON, new IllegalArgumentException());
		}

		try (var httpInterface = this.sourceManager.getHttpInterface()) {
			try (var stream = new PersistentHttpStream(httpInterface, new URI(this.previewUrl), this.trackInfo.length)) {
				processDelegate(createAudioTrack(this.trackInfo, stream), executor);
			}
		} catch (Exception e) {
			log.error("Failed to process preview from URL: {}", this.previewUrl, e);
			throw new FriendlyException("Failed to load preview", 
				FriendlyException.Severity.COMMON, e);
		}
	}

	private InternalAudioTrack extractPlayableTrack(AudioItem item) {
		if (item instanceof InternalAudioTrack) {
			return (InternalAudioTrack) item;
		}

		if (item instanceof AudioPlaylist) {
			var tracks = ((AudioPlaylist) item).getTracks();
			if (!tracks.isEmpty() && tracks.get(0) instanceof InternalAudioTrack) {
				return (InternalAudioTrack) tracks.get(0);
			}
		}

		return null;
	}

	@Override
	public AudioSourceManager getSourceManager() {
		return this.sourceManager;
	}

	public AudioItem loadItem(String query) {
		CompletableFuture<AudioItem> future = new CompletableFuture<>();
		
		this.sourceManager.getAudioPlayerManager().loadItem(query, new AudioLoadResultHandler() {
			@Override
			public void trackLoaded(AudioTrack track) {
				log.trace("Track loaded: {}", track.getIdentifier());
				future.complete(track);
			}

			@Override
			public void playlistLoaded(AudioPlaylist playlist) {
				log.trace("Playlist loaded: {} ({} tracks)", playlist.getName(), playlist.getTracks().size());
				future.complete(playlist);
			}

			@Override
			public void noMatches() {
				log.trace("No matches for query: {}", query);
				future.complete(AudioReference.NO_TRACK);
			}

			@Override
			public void loadFailed(FriendlyException exception) {
				log.warn("Load failed for query: {} - {}", query, exception.getMessage());
				future.completeExceptionally(exception);
			}
		});

		try {
			return future.get(LOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (Exception e) {
			log.error("Timeout or error loading item: {}", query, e);
			return AudioReference.NO_TRACK;
		}
	}

}