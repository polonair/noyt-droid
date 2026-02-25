def hello():
    return "py ok"


def ytdlp_version():
    try:
        import yt_dlp

        if hasattr(yt_dlp, "version") and hasattr(yt_dlp.version, "__version__"):
            return yt_dlp.version.__version__
        if hasattr(yt_dlp, "__version__"):
            return yt_dlp.__version__
        return "yt_dlp version attribute not found"
    except Exception as exc:
        return str(exc)


def latest_video_url(channel_id: str) -> str | None:
    try:
        import yt_dlp

        channel_url = f"https://www.youtube.com/channel/{channel_id}/videos"

        def to_video_url(entry: dict) -> str | None:
            url = entry.get("url")
            if isinstance(url, str):
                if url.startswith("http://") or url.startswith("https://"):
                    if "/channel/" in url and url.endswith("/videos"):
                        return None
                    return url
                return f"https://www.youtube.com/watch?v={url}"

            webpage_url = entry.get("webpage_url")
            if isinstance(webpage_url, str):
                if "/channel/" in webpage_url and webpage_url.endswith("/videos"):
                    return None
                return webpage_url

            video_id = entry.get("id")
            if isinstance(video_id, str):
                return f"https://www.youtube.com/watch?v={video_id}"

            return None

        with yt_dlp.YoutubeDL({
            "quiet": True,
            "skip_download": True,
            "extract_flat": True,
            "playlistend": 1,
            "noplaylist": False,
        }) as ydl:
            info = ydl.extract_info(channel_url, download=False)

        entries = info.get("entries") if isinstance(info, dict) else None
        if not entries:
            return None

        first = entries[0]
        if not isinstance(first, dict):
            return None

        first_url = to_video_url(first)
        if first_url:
            return first_url

        nested_url = None
        for field in ("url", "webpage_url"):
            candidate = first.get(field)
            if not isinstance(candidate, str):
                continue

            if "/channel/" in candidate and candidate.endswith("/videos"):
                nested_url = candidate
                break

            if candidate.startswith("/") and candidate.endswith("/videos"):
                nested_url = candidate
                break

        if not nested_url:
            return None

        if not (nested_url.startswith("http://") or nested_url.startswith("https://")):
            nested_url = f"https://www.youtube.com{nested_url}"

        with yt_dlp.YoutubeDL({
            "quiet": True,
            "skip_download": True,
            "extract_flat": True,
            "playlistend": 1,
            "noplaylist": False,
        }) as ydl:
            nested_info = ydl.extract_info(nested_url, download=False)

        nested_entries = nested_info.get("entries") if isinstance(nested_info, dict) else None
        if not nested_entries:
            return None

        nested_first = nested_entries[0]
        if not isinstance(nested_first, dict):
            return None

        return to_video_url(nested_first)
    except Exception:
        return None


def download_best_audio(url: str, out_dir: str, base_name: str) -> str:
    try:
        import glob
        import os
        import yt_dlp

        os.makedirs(out_dir, exist_ok=True)
        outtmpl = os.path.join(out_dir, base_name + ".%(ext)s")

        with yt_dlp.YoutubeDL({
            "format": "bestaudio/best",
            "outtmpl": outtmpl,
            "noplaylist": True,
            "quiet": True,
        }) as ydl:
            info = ydl.extract_info(url, download=False)
            expected_path = ydl.prepare_filename(info)
            ydl.download([url])

        if os.path.exists(expected_path):
            return expected_path

        matches = glob.glob(os.path.join(out_dir, base_name + ".*"))
        if matches:
            matches.sort(key=lambda p: os.path.getmtime(p), reverse=True)
            return matches[0]

        return "ERROR: Download finished, but file not found"
    except Exception as exc:
        return f"ERROR: {exc}"
