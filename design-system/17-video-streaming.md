# Video Streaming Platform — Deep Design Guide

## Problem Statement

Design a video streaming platform like Netflix or YouTube — where users upload videos, the platform processes and transcodes them into multiple quality levels, and viewers stream them globally with adaptive bitrate based on network conditions.

---

## Why This Problem Matters

Video streaming is one of the most data-intensive, globally distributed, and latency-sensitive domains in software engineering. Netflix alone accounts for ~15% of global internet traffic. The design touches every layer of the stack:

- **Upload pipeline**: large file handling, resumable uploads, distributed transcoding
- **Storage at extreme scale**: exabytes of video, tiered by access frequency (hot/warm/cold)
- **CDN architecture**: how to serve video segments to 200M subscribers across 190 countries with sub-100ms TTFB
- **Adaptive bitrate streaming**: the algorithm that lets video "just work" on a 4G connection or 1Gbps fiber

**What interviewers are testing**: Whether you understand HLS/DASH and why adaptive bitrate exists, how you architect the transcoding pipeline (it's a distributed batch job, not a microservice), the CDN caching strategy for video vs. thumbnails vs. manifests, the presigned URL pattern for large file uploads, and how you handle the Netflix-style recommendation system at a high level.

---

## Key Insight Before Diving In

**Video streaming is fundamentally a CDN cache-hit optimization problem. Every engineering decision — from segment duration to manifest TTL to upload flow — is made in service of maximizing CDN cache hit rate.**

If a video segment is cached at a CDN edge node in Paris, a viewer in Paris gets it at 1-5ms latency with zero load on origin servers. If it's not cached, the request travels to origin (potentially on another continent), loads servers, and introduces 100ms+ latency. The difference between a well-designed and poorly-designed streaming system is almost entirely in the CDN strategy.

The second insight: **never upload directly to your application servers**. A 50GB video file uploaded through your API creates backpressure, consumes thread pools, and requires your servers to forward it to storage. Use presigned S3 URLs to let clients upload directly to S3, bypassing your application tier entirely.

---

## Requirements

### Functional
- Upload videos up to 50GB; support resumable uploads (for poor connections)
- Transcode to multiple resolutions: 240p, 360p, 480p, 720p, 1080p, 4K
- Stream with adaptive bitrate (auto quality based on bandwidth)
- Thumbnail generation; video preview on hover
- Search by title, tag, channel
- Comments, likes, subscriptions, notifications
- Watch history and resume playback from last position
- Creator analytics: views, watch time, revenue

### Non-Functional
- 1M concurrent streams globally (Netflix scale)
- Video start time (TTFB): < 1 second
- Zero buffering for 95% of watch sessions (adaptive bitrate must handle degraded networks)
- Upload throughput: 100k videos/day
- Storage: multi-exabyte scale, tiered by access frequency
- 99.99% streaming availability

---

## Capacity Estimation

```
Upload:
  100k videos/day × avg 500MB raw = 50TB/day ingested
  After transcoding (5 renditions, avg 3:1 compression): 50TB × 5 × 0.33 = 82TB/day output
  5-year storage: 82TB/day × 365 × 5 = 150PB (petabytes) → tiered storage

Streaming:
  1M concurrent streams × 5Mbps avg = 5Tbps egress bandwidth
  This is served entirely by CDN — origin servers see < 1% of this traffic

CDN cache hit rate target: 99%
  → 1% of 5Tbps = 50Gbps from origin (manageable with 100-1000 origin servers)

Transcoding compute:
  1 hour of 1080p video → transcoding to 5 renditions takes ~30 min of CPU
  100k videos/day × 30 min = 3M CPU-minutes/day = 2,083 CPU-hours/day
  → 87 servers running 24/7, or 2,083 spot instances running 1 hour each

Thumbnail generation:
  100k videos × 10 thumbnails = 1M images/day (trivial)
```

---

## Video Upload Flow

```
Step 1: Creator requests upload URL
  POST /uploads
  { "filename": "my-video.mp4", "size_bytes": 5368709120, "content_type": "video/mp4" }

  Server:
  - Create video record (status=UPLOADING)
  - Call S3 API: create_multipart_upload()
  - Generate 53 presigned URLs (one per 100MB part)
  - Return: { upload_id, parts: [{ part_number, presigned_url }], video_id }

Step 2: Client uploads parts directly to S3 (parallel)
  PUT {presigned_url_1}  ← part 1 (100MB)
  PUT {presigned_url_2}  ← part 2 (100MB)
  ... all 53 parts in parallel (limited by client bandwidth)

  WHY DIRECT TO S3:
  - Application servers never see a single byte of video
  - No backpressure on your API tier
  - S3 handles the distributed write internally
  - Client can pause and resume (just skip completed parts)
  - S3 is designed for this exact pattern

Step 3: Client signals completion
  POST /uploads/{uploadId}/complete
  { "parts": [{ "part_number": 1, "etag": "abc" }, ...] }

  Server:
  - S3 API: complete_multipart_upload() — S3 assembles the parts
  - Update video record: status=PROCESSING
  - Publish to Kafka: video.uploaded { video_id, s3_path, creator_id }

Step 4: Transcoding pipeline picks up the event (async)
  Kafka consumer → Transcoding Service → Schedule transcoding jobs
```

---

## Transcoding Pipeline

### Why Transcoding is Necessary

Different viewers have different bandwidths. A 1080p stream at 5Mbps is unwatchable on a mobile with 1Mbps bandwidth. Transcoding creates multiple versions (renditions) so the player can select the appropriate one.

```
Raw video (1080p, H.264, 3GB, 30 min)
                │
                ▼
         FFmpeg Transcoder Worker
                │
    ┌───────────┼─────────────────────────────┐
    ▼           ▼           ▼           ▼     ▼
 240p/400kbps 480p/1Mbps 720p/2.5Mbps 1080p/5Mbps 4K/15Mbps
  (mobile)    (tablet)   (laptop)    (TV-HD)    (TV-4K)
    │           │           │           │         │
    └───────────┴───────────┴───────────┴─────────┘
                │
       HLS Segmentation (each rendition)
       2-second segments (.ts files)
       + manifest file (.m3u8)
                │
       Upload to S3 transcoded bucket
                │
       Invalidate CDN cache (or warm it proactively)
```

### FFmpeg Command (simplified)

```bash
# Transcode raw video to 720p HLS segments
ffmpeg \
  -i s3://raw-bucket/video-123.mp4 \        # input from S3 (streaming)
  -vf scale=1280:720 \                        # scale to 720p
  -c:v libx264 \                              # H.264 codec
  -b:v 2500k \                                # 2.5Mbps target bitrate
  -c:a aac \                                  # AAC audio
  -b:a 128k \                                 # 128kbps audio
  -hls_time 2 \                               # 2-second segments
  -hls_playlist_type vod \                    # VOD (not live)
  -hls_segment_filename 'seg_%03d.ts' \       # segment naming
  -start_number 0 \
  output_720p.m3u8                            # manifest file
```

### Parallelizing Transcoding

A 2-hour film transcoded sequentially takes hours. Parallelize by splitting the video into chunks:

```
Raw video (2 hours) → split into 10 × 12-minute chunks
  Each chunk → 5 renditions = 50 parallel FFmpeg jobs
  Each FFmpeg job → separate EC2 Spot instance or Fargate task
  50 jobs × 12 min = 2 hours of transcoding parallelized into 12 minutes wall clock

Chunk splitting: ffmpeg -ss {start} -t {duration} -i input.mp4 -c copy chunk_{n}.mp4
Reassembly: ffmpeg -f concat -safe 0 -i list.txt -c copy full_video.ts
```

---

## HLS (HTTP Live Streaming) — The Adaptive Bitrate Protocol

### Manifest Structure

```
# Master playlist (video.m3u8) — points to rendition playlists
#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=400000,RESOLUTION=426x240,CODECS="avc1.42c00d,mp4a.40.2"
240p/index.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=854x480,CODECS="avc1.4d401e,mp4a.40.2"
480p/index.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720,CODECS="avc1.4d401f,mp4a.40.2"
720p/index.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2"
1080p/index.m3u8
```

```
# 720p rendition playlist (720p/index.m3u8)
#EXTM3U
#EXT-X-VERSION:3
#EXT-X-TARGETDURATION:2
#EXT-X-MEDIA-SEQUENCE:0
#EXTINF:2.000,
seg_000.ts      ← segment 0: bytes 0-2 seconds
#EXTINF:2.000,
seg_001.ts      ← segment 1: bytes 2-4 seconds
...
#EXT-X-ENDLIST
```

### Adaptive Bitrate Logic

```
Player monitors download speed of each segment:
  Segment 0 (720p, 625KB): downloaded in 0.5s → throughput = 10Mbps
  → 10Mbps > 5Mbps (1080p threshold) → switch to 1080p
  Segment 1 (1080p, 1250KB): downloaded in 3s → throughput = 3.3Mbps
  → 3.3Mbps < 5Mbps → downgrade to 720p
  Segment 2 (720p): downloaded in 0.8s → throughput = 6.25Mbps
  → stable at 720p

Buffer strategy:
  Player maintains 30-second buffer ahead of playback position
  As long as buffer > 15 seconds: aggressive (try higher quality)
  As buffer decreases (network getting worse): conservatively downgrade
  Buffer exhausted: playback stalls → this is what "buffering" means
```

---

## CDN Strategy — The Core of the Architecture

```
CDN Architecture:
  Tier 1: PoP (Point of Presence) — edge nodes in 200+ cities worldwide
  Tier 2: Regional cache — 20 regional hubs (aggregates edge misses)
  Tier 3: Origin — S3 (ultimate source)

Cache TTL per content type:
  Video segments (.ts files):   30 days (immutable once created — segment N is always the same bytes)
  Master manifest (.m3u8):      5 seconds for LIVE, 24 hours for VOD (changes after upload processing)
  Thumbnail images:             7 days
  Metadata (title, description): 60 seconds

Cache key:
  /videos/{video_id}/{rendition}/seg_{n}.ts
  → Unique per video, rendition, and segment number
  → Immutable: same URL always serves same bytes → perfect CDN caching

Pre-warming (for popular releases):
  New episode of a hit series → will get 10M views in first hour
  Before release: CDN warms edge nodes with the video segments
  → Request hits edge immediately (no origin fetch delay for first viewer)
```

---

## Resume Playback

```
Every 15 seconds during playback → client sends progress:
  POST /videos/{videoId}/progress
  { "position_sec": 1247, "session_id": "..." }

  Server:
  - Redis SET user:{userId}:progress:{videoId} 1247 EX 2592000  (30 days TTL)

On video open:
  GET /videos/{videoId}/progress
  → Redis GET user:{userId}:progress:{videoId}
  → If exists: player.seek(position_sec) before starting playback
  → If not: start from beginning

Why Redis? Progress is session-like data: fast reads, tolerates loss, short TTL.
No need for ACID database for this use case.
```

---

## Data Model

```sql
-- Videos
CREATE TABLE videos (
  id              UUID PRIMARY KEY,
  creator_id      UUID NOT NULL,
  title           VARCHAR(500) NOT NULL,
  description     TEXT,
  status          VARCHAR(20) DEFAULT 'UPLOADING',
    -- UPLOADING, PROCESSING, PUBLISHED, PRIVATE, DELETED
  duration_sec    INT,
  file_size_bytes BIGINT,
  thumbnail_url   TEXT,
  preview_url     TEXT,       -- 3-second animated preview (GIF/WebP)
  view_count      BIGINT DEFAULT 0,
  like_count      BIGINT DEFAULT 0,
  tags            TEXT[],
  category        VARCHAR(50),
  language        CHAR(2),
  published_at    TIMESTAMPTZ,
  created_at      TIMESTAMPTZ DEFAULT NOW()
);

-- Video renditions (one per quality level)
CREATE TABLE video_renditions (
  video_id     UUID REFERENCES videos(id),
  rendition    VARCHAR(10),  -- '240p', '480p', '720p', '1080p', '4k'
  status       VARCHAR(20),  -- PENDING, PROCESSING, READY, FAILED
  s3_path      TEXT,         -- s3://transcoded/video-id/720p/
  manifest_url TEXT,         -- CDN URL to m3u8 manifest
  bitrate_kbps INT,
  width        INT,
  height       INT,
  file_size_mb INT,
  PRIMARY KEY (video_id, rendition)
);

-- Watch history (Cassandra — append-heavy, time-series per user)
CREATE TABLE watch_history (
  user_id      UUID,
  video_id     UUID,
  watched_at   TIMESTAMP,
  watch_pct    DECIMAL(5,2),  -- 0.00 - 100.00 (% of video watched)
  position_sec INT,
  completed    BOOLEAN,
  PRIMARY KEY ((user_id), watched_at, video_id)
) WITH CLUSTERING ORDER BY (watched_at DESC);
-- Partition by user_id, cluster by time → fast "user's recent watches" query
```

---

## Tech Stack

- **Upload**: AWS S3 Multipart + Presigned URLs (direct client-to-S3 upload)
- **Transcoding**: FFmpeg on AWS Fargate/Batch (auto-scaling spot instances)
- **Video Storage**: AWS S3 (tiered: S3 Standard → Infrequent Access → Glacier)
- **Streaming Protocol**: HLS (Apple) / MPEG-DASH (Android) — both supported
- **CDN**: AWS CloudFront (200+ global PoPs)
- **Metadata DB**: PostgreSQL (video details, creator, renditions)
- **Watch History**: Cassandra (time-series, append-heavy)
- **Progress Cache**: Redis
- **Search**: ElasticSearch (title, tags, full-text)
- **Events**: Kafka (video.uploaded → transcoder; video.published → notification)
- **Recommendations**: Apache Spark (offline ML) → Redis (serve top-N per user)

---

## Interview Q&A

**Q1: Why is HLS better than just serving a raw MP4 file for video streaming?**

A: A raw MP4 requires downloading the entire file before playback begins, or complex range-request byte-serving that doesn't support adaptive bitrate. HLS divides the video into small segments (2-10 seconds each) that are independent HTTP GET requests. This enables: (1) **Adaptive bitrate**: the player can switch quality between segments without seeking or rebuffering; (2) **CDN friendliness**: each segment is a small, independent, cacheable object — CDNs are optimized for small files, not gigabyte files; (3) **Fast start**: playback can begin after buffering just the first few segments (< 10 seconds of video), not after downloading the entire file; (4) **Seeking**: jump to any position by requesting the appropriate segments starting at that timestamp; (5) **DRM integration**: HLS supports AES-128 encryption per segment and Apple FairPlay DRM.

---

**Q2: How do you handle the transcoding of a 4K, 2-hour film efficiently?**

A: Sequential transcoding of a 2-hour 4K film takes 4+ hours on a single server. The solution is parallel transcoding at two levels: (1) **Per-rendition parallelism**: transcode all 5 renditions simultaneously on separate servers (5× speedup); (2) **Temporal parallelism**: split the video into 10-minute chunks, transcode each chunk separately across 10 servers, then concatenate. Combined: 5 renditions × 12 chunks = 60 parallel FFmpeg jobs, reducing wall-clock time from 4 hours to ~5 minutes. AWS Fargate Spot instances are ideal: spin up 60 instances for the transcoding job, terminate when done (pay only for usage). The challenge is chunk splitting without re-encoding: use `-c copy` (stream copy) for splitting — no quality loss, instant.

---

**Q3: Why use 2-second segments instead of 10-second segments? What is the tradeoff?**

A: Segment duration controls the adaptation latency vs. overhead tradeoff. **Shorter segments (2-4s)**: the player can switch quality every 2 seconds → faster adaptation to bandwidth changes → better experience on variable connections. But: more files to store (a 2-hour video in 5 renditions = 18,000 segments), more HTTP requests (seek generates more segment requests), more CDN cache pressure (tiny files cached less efficiently). **Longer segments (10s)**: fewer files, more efficient CDN caching, but slow adaptation (player stuck on wrong quality for up to 10 seconds after bandwidth change). **Industry standard**: 2-4 seconds for live streaming (low latency critical), 6-10 seconds for VOD (adaptation speed less critical, efficiency matters more). Netflix typically uses 4-second segments for VOD.

---

**Q4: A video goes viral and suddenly gets 10 million concurrent viewers. How does the system handle this?**

A: This is the CDN hit rate problem. A viral video has virtually 100% CDN hit rate after the first few seconds because: (1) every segment is the same bytes for everyone at the same quality level; (2) once the first viewer's request causes a CDN cache miss (origin fetch), all subsequent requests to that CDN edge node are cache hits. The actual load on origin servers is proportional to the number of CDN edge nodes serving the video, not the number of viewers. With 200 edge nodes globally, 10M viewers may generate only 200-1000 origin requests (one per edge node per segment, cached after first hit). The bottleneck at 10M concurrent streams is CDN egress bandwidth (10M × 5Mbps = 50Tbps) — which CloudFront/Akamai handle through their massive peering infrastructure.

---

**Q5: How do you handle video content that was deleted by the creator but is still cached in the CDN?**

A: Video deletion must propagate to CDN to prevent serving deleted content. Approach: (1) **Soft delete in DB**: `status = 'DELETED'`, `deleted_at = NOW()`. The metadata API returns 404 for deleted videos. (2) **CDN cache invalidation**: `POST /2020-11-01/distribution/{id}/invalidation { Paths: { Items: ["/videos/deleted-video-id/*"] } }`. This purges all cached objects under that path from all CDN edge nodes. CloudFront invalidation propagates in < 60 seconds globally. (3) **Authorization token on manifests**: generate time-limited signed URLs for manifests. Even if cached, a manifest accessed with an expired token returns 403 from CDN. This is the most robust approach for DRM/legal compliance. (4) **Tombstone in Redis**: `SET deleted:{video_id} 1 EX 86400`. Any request that reaches origin checks this before serving.

---

**Q6: How does the recommendation system decide what to show in the "Up Next" feed?**

A: Netflix-style recommendation at a high level: (1) **Collaborative filtering**: "users who watched A and B also watched C" — matrix factorization on the user-video watch matrix; (2) **Content-based filtering**: "you watched action movies with these actors — here are similar ones" — using video embeddings (from title, description, genre, tags); (3) **Recency signals**: recently watched genres/creators get higher weight; (4) **Diversity**: avoid showing 5 videos from the same creator consecutively; (5) **Business rules**: promote new releases, surface catalog gems with low discovery rates. Offline batch job (Apache Spark) runs nightly, computes top-50 recommendations per user, stores in Redis: `user:{id}:recommendations → [video_id_list]`. Serving is O(1) Redis read. Real-time signals (just watched X) are incorporated in the next batch run or via lightweight online adjustment.

---

**Q7: How do you implement content moderation for uploaded videos?**

A: Content moderation is a multi-stage async pipeline that runs after upload but before publication: (1) **Automated scanning**: AWS Rekognition Video API scans for explicit content (adult content, violence, text overlays). Runs in parallel with transcoding. (2) **Hash matching**: compute perceptual hash (pHash) of video frames; compare against database of known illegal content (PhotoDNA for CSAM). Any match → immediate removal + law enforcement notification. (3) **Audio transcription**: Whisper AI transcribes audio → scan transcript for hate speech, copyright violations. (4) **Copyright ID**: compare audio fingerprint against music licensing database (like YouTube's Content ID). Flagged videos: copyright holder can monetize, mute, or remove. (5) **Human review queue**: videos flagged by automated systems go to human reviewers before publication. Queue prioritized by signal severity. Turnaround: < 24 hours for flagged content, < 1 hour for CRITICAL flags.