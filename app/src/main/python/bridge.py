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

        channel_url = f"https://www.youtube.com/channel/{channel_id}"
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

        url = first.get("url")
        if url and isinstance(url, str):
            if url.startswith("http://") or url.startswith("https://"):
                return url
            return f"https://www.youtube.com/watch?v={url}"

        webpage_url = first.get("webpage_url")
        if webpage_url and isinstance(webpage_url, str):
            return webpage_url

        video_id = first.get("id")
        if video_id and isinstance(video_id, str):
            return f"https://www.youtube.com/watch?v={video_id}"

        return None
    except Exception:
        return None
