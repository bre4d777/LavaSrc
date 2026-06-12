package com.github.topi314.lavasrc.mirror;

import com.github.topi314.lavasrc.applemusic.AppleMusicSourceManager;
import com.github.topi314.lavasrc.spotify.SpotifySourceManager;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class DefaultMirroringAudioTrackResolver implements MirroringAudioTrackResolver {

	private static final Logger log = LoggerFactory.getLogger(DefaultMirroringAudioTrackResolver.class);

	private static final long DURATION_TOLERANCE_MS = 5000;
	private static final double MIN_SIMILARITY_THRESHOLD = 0.55;
	private static final double TITLE_WEIGHT = 0.45;
	private static final double ARTIST_WEIGHT = 0.35;
	private static final double DURATION_WEIGHT = 0.20;

	private String[] providers = {
		"ytsearch:\"" + MirroringAudioSourceManager.ISRC_PATTERN + "\"",
		"ytsearch:" + MirroringAudioSourceManager.QUERY_PATTERN
	};

	public DefaultMirroringAudioTrackResolver(String[] providers) {
		if (providers != null && providers.length > 0) {
			this.providers = providers;
		}
	}

	@Override
	public AudioItem apply(MirroringAudioTrack mirroringAudioTrack) {
		AudioTrack bestMatch = null;
		double bestScore = 0.0;
		String bestProvider = null;

		for (var provider : providers) {
			if (provider.startsWith(SpotifySourceManager.SEARCH_PREFIX)) {
				log.warn("Cannot use Spotify search as provider, skipping");
				continue;
			}

			if (provider.startsWith(AppleMusicSourceManager.SEARCH_PREFIX)) {
				log.warn("Cannot use Apple Music search as provider, skipping");
				continue;
			}

			String resolvedProvider = resolveProvider(provider, mirroringAudioTrack);
			if (resolvedProvider == null) {
				continue;
			}

			AudioItem item;
			try {
				item = mirroringAudioTrack.loadItem(resolvedProvider);
			} catch (Exception e) {
				log.error("Failed to load from provider: {}", resolvedProvider, e);
				continue;
			}

			if (item == null || item == AudioReference.NO_TRACK) {
				continue;
			}

			List<AudioTrack> candidates = extractTracks(item);
			if (candidates.isEmpty()) {
				continue;
			}

			AudioTrack match = findBestMatch(mirroringAudioTrack, candidates);
			if (match != null) {
				double score = calculateMatchScore(mirroringAudioTrack, match);
				log.debug("Provider {} match score: {} for track: {}", 
					resolvedProvider, score, match.getInfo().title);

				if (score > bestScore) {
					bestScore = score;
					bestMatch = match;
					bestProvider = resolvedProvider;
				}

				if (score >= 0.75) {
					log.info("Match found: [{}] \"{}\" by \"{}\" ({}ms) => [{}] \"{}\" by \"{}\" ({}ms) | Score: {}", 
						mirroringAudioTrack.getSourceManager().getSourceName(),
						mirroringAudioTrack.getInfo().title,
						mirroringAudioTrack.getInfo().author,
						mirroringAudioTrack.getInfo().length,
						resolvedProvider.split(":")[0],
						match.getInfo().title,
						match.getInfo().author,
						match.getInfo().length,
						String.format("%.2f", score)
					);
					log.debug("High confidence match found (score: {}), using immediately", score);
					return match;
				}
			}
		}

		if (bestMatch != null && bestScore >= MIN_SIMILARITY_THRESHOLD) {
			log.info("Match found: [{}] \"{}\" by \"{}\" ({}ms) => [{}] \"{}\" by \"{}\" ({}ms) | Score: {}", 
				mirroringAudioTrack.getSourceManager().getSourceName(),
				mirroringAudioTrack.getInfo().title,
				mirroringAudioTrack.getInfo().author,
				mirroringAudioTrack.getInfo().length,
				bestProvider != null ? bestProvider.split(":")[0] : "unknown",
				bestMatch.getInfo().title,
				bestMatch.getInfo().author,
				bestMatch.getInfo().length,
				String.format("%.2f", bestScore)
			);
			log.debug("Best match selected with score: {} - {}", bestScore, bestMatch.getInfo().title);
			return bestMatch;
		}

		log.debug("No suitable match found (best score: {})", bestScore);
		return AudioReference.NO_TRACK;
	}

	private String resolveProvider(String provider, MirroringAudioTrack track) {
		if (provider.contains(MirroringAudioSourceManager.ISRC_PATTERN)) {
			if (track.getInfo().isrc != null && !track.getInfo().isrc.isEmpty()) {
				return provider.replace(MirroringAudioSourceManager.ISRC_PATTERN, 
					track.getInfo().isrc.replace("-", ""));
			} else {
				log.debug("Skipping ISRC provider - track has no ISRC");
				return null;
			}
		}

		if (provider.contains(MirroringAudioSourceManager.QUERY_PATTERN)) {
			return provider.replace(MirroringAudioSourceManager.QUERY_PATTERN, 
				getTrackTitle(track));
		}

		return provider;
	}

	private List<AudioTrack> extractTracks(AudioItem item) {
		List<AudioTrack> tracks = new ArrayList<>();
		
		if (item instanceof AudioTrack) {
			tracks.add((AudioTrack) item);
		} else if (item instanceof AudioPlaylist) {
			tracks.addAll(((AudioPlaylist) item).getTracks());
		}
		
		return tracks;
	}

	private AudioTrack findBestMatch(MirroringAudioTrack original, List<AudioTrack> candidates) {
		AudioTrack bestMatch = null;
		double bestScore = 0.0;

		int limit = Math.min(candidates.size(), 10);
		
		for (int i = 0; i < limit; i++) {
			AudioTrack candidate = candidates.get(i);
			double score = calculateMatchScore(original, candidate);
			
			log.debug("Candidate {}: \"{}\" by \"{}\" | Score: {}", 
				i + 1, 
				candidate.getInfo().title, 
				candidate.getInfo().author,
				String.format("%.2f", score)
			);
			
			if (score > bestScore) {
				bestScore = score;
				bestMatch = candidate;
			}
			
			if (score >= 0.98) {
				break;
			}
		}

		return (bestScore >= MIN_SIMILARITY_THRESHOLD) ? bestMatch : null;
	}

	private double calculateMatchScore(MirroringAudioTrack original, AudioTrack candidate) {
		String originalTitle = original.getInfo().title;
		String candidateTitle = candidate.getInfo().title;
		String originalArtist = original.getInfo().author;
		String candidateArtist = candidate.getInfo().author;
		
		//  for exact matches FIRST 
		String originalTitleLower = originalTitle.trim().toLowerCase();
		String candidateTitleLower = candidateTitle.trim().toLowerCase();
		String originalArtistLower = originalArtist.trim().toLowerCase();
		String candidateArtistLower = candidateArtist.trim().toLowerCase();
		
		// EXACT title match 
		if (originalTitleLower.equals(candidateTitleLower)) {
			double artistScore = calculateStringSimilarity(
				normalize(originalArtist),
				normalize(candidateArtist)
			);
			double durationScore = calculateDurationSimilarity(
				original.getInfo().length,
				candidate.getInfo().length
			);
			return (1.0 * TITLE_WEIGHT) + (artistScore * ARTIST_WEIGHT) + (durationScore * DURATION_WEIGHT);
		}
		
		// Title starts with query - very strong match
		if (candidateTitleLower.startsWith(originalTitleLower)) {
			double artistScore = calculateStringSimilarity(
				normalize(originalArtist),
				normalize(candidateArtist)
			);
			double durationScore = calculateDurationSimilarity(
				original.getInfo().length,
				candidate.getInfo().length
			);
			return (0.95 * TITLE_WEIGHT) + (artistScore * ARTIST_WEIGHT) + (durationScore * DURATION_WEIGHT);
		}
		
		// Query is contained in title
		if (candidateTitleLower.contains(originalTitleLower)) {
			double artistScore = calculateStringSimilarity(
				normalize(originalArtist),
				normalize(candidateArtist)
			);
			double durationScore = calculateDurationSimilarity(
				original.getInfo().length,
				candidate.getInfo().length
			);
			return (0.90 * TITLE_WEIGHT) + (artistScore * ARTIST_WEIGHT) + (durationScore * DURATION_WEIGHT);
		}
		
		// normalized comparison for fuzzy matching
		double titleScore = calculateStringSimilarity(
			normalize(originalTitle),
			normalize(candidateTitle)
		);

		double artistScore = calculateStringSimilarity(
			normalize(originalArtist),
			normalize(candidateArtist)
		);

		double durationScore = calculateDurationSimilarity(
			original.getInfo().length,
			candidate.getInfo().length
		);

		double totalScore = (titleScore * TITLE_WEIGHT) + 
		                   (artistScore * ARTIST_WEIGHT) + 
		                   (durationScore * DURATION_WEIGHT);

		return totalScore;
	}

	private double calculateStringSimilarity(String s1, String s2) {
		if (s1.equals(s2)) {
			return 1.0;
		}

		if (s1.isEmpty() || s2.isEmpty()) {
			return 0.0;
		}

		if (s1.contains(s2) || s2.contains(s1)) {
			return 0.85;
		}

		int distance = levenshteinDistance(s1, s2);
		int maxLen = Math.max(s1.length(), s2.length());
		
		if (maxLen == 0) {
			return 1.0;
		}

		return 1.0 - ((double) distance / maxLen);
	}

	private double calculateDurationSimilarity(long duration1, long duration2) {
		if (duration1 <= 0 || duration2 <= 0) {
			return 0.5;
		}

		long diff = Math.abs(duration1 - duration2);
		
		if (diff <= DURATION_TOLERANCE_MS) {
			return 1.0;
		}

		long maxDuration = Math.max(duration1, duration2);
		double ratio = 1.0 - ((double) diff / maxDuration);
		
		return Math.max(0.0, ratio);
	}

	private int levenshteinDistance(String s1, String s2) {
		int len1 = s1.length();
		int len2 = s2.length();
		
		int[][] dp = new int[len1 + 1][len2 + 1];
		
		for (int i = 0; i <= len1; i++) {
			dp[i][0] = i;
		}
		
		for (int j = 0; j <= len2; j++) {
			dp[0][j] = j;
		}
		
		for (int i = 1; i <= len1; i++) {
			for (int j = 1; j <= len2; j++) {
				int cost = (s1.charAt(i - 1) == s2.charAt(j - 1)) ? 0 : 1;
				dp[i][j] = Math.min(
					Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
					dp[i - 1][j - 1] + cost
				);
			}
		}
		
		return dp[len1][len2];
	}

	private String normalize(String str) {
		if (str == null) {
			return "";
		}
		
		return str.toLowerCase()
			.replaceAll("\\s*\\(.*?\\)", "")
			.replaceAll("\\s*\\[.*?\\]", "")
			.replaceAll("feat\\.?|ft\\.?", "")
			.replaceAll("[^a-z0-9\\s]", "")
			.replaceAll("\\s+", " ")
			.trim();
	}

	public String getTrackTitle(MirroringAudioTrack mirroringAudioTrack) {
		var query = mirroringAudioTrack.getInfo().title;
		if (!mirroringAudioTrack.getInfo().author.equals("unknown")) {
			query += " " + mirroringAudioTrack.getInfo().author;
		}
		return query;
	}

}