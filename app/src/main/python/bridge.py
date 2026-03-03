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
        safe_name = os.path.basename(base_name).replace(os.sep, "_")
        if os.altsep:
            safe_name = safe_name.replace(os.altsep, "_")
        safe_name = safe_name.strip(" .") or "audio"
        outtmpl = os.path.join(out_dir, safe_name + ".%(ext)s")

        with yt_dlp.YoutubeDL({
            "format": "bestaudio/best",
            "outtmpl": outtmpl,
            "noplaylist": True,
            "quiet": True,
        }) as ydl:
            info = ydl.extract_info(url, download=False)
            expected_path = ydl.prepare_filename(info)
            expected_abs = os.path.abspath(expected_path)
            allowed_root = os.path.abspath(out_dir)
            if os.path.commonpath([expected_abs, allowed_root]) != allowed_root:
                return "ERROR: Invalid output path"
            ydl.download([url])

        if os.path.exists(expected_abs):
            return expected_abs

        matches = glob.glob(os.path.join(out_dir, safe_name + ".*"))
        if matches:
            matches.sort(key=lambda p: os.path.getmtime(p), reverse=True)
            return matches[0]

        return "ERROR: Download finished, but file not found"
    except Exception as exc:
        return f"ERROR: {exc}"


def resolve_channel_fast(url_or_handle: str) -> dict:
    import html
    import re
    import urllib.request

    source = (url_or_handle or "").strip()
    if not source:
        raise ValueError("Channel link is empty")

    if source.startswith("@"):
        source = f"https://www.youtube.com/{source}"
    elif not re.match(r"^https?://", source, flags=re.IGNORECASE):
        source = f"https://{source}"

    print(f"resolve_channel_fast: start source={source}")

    headers = {
        "User-Agent": (
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            "AppleWebKit/537.36 (KHTML, like Gecko) "
            "Chrome/131.0.0.0 Safari/537.36"
        ),
        "Accept-Language": "en-US,en;q=0.9",
        "Accept": "text/html,*/*",
    }

    html_text = ""
    status_code = None
    final_url = source
    try:
        import requests

        response = requests.get(source, headers=headers, timeout=(10, 15), allow_redirects=True)
        response.raise_for_status()
        status_code = response.status_code
        final_url = response.url
        html_text = response.text
    except ImportError:
        request = urllib.request.Request(source, headers=headers)
        with urllib.request.urlopen(request, timeout=15) as response:
            status_code = getattr(response, "status", None)
            final_url = response.geturl()
            html_text = response.read().decode("utf-8", errors="ignore")

    print(f"resolve_channel_fast: status={status_code} final_url={final_url}")

    def meta_property(name: str) -> str | None:
        pattern = re.compile(
            r'<meta[^>]+property=["\']{}["\'][^>]+content=["\']([^"\']+)["\']'.format(re.escape(name)),
            flags=re.IGNORECASE,
        )
        match = pattern.search(html_text)
        return html.unescape(match.group(1).strip()) if match else None

    def title_tag() -> str | None:
        match = re.search(r"<title[^>]*>(.*?)</title>", html_text, flags=re.IGNORECASE | re.DOTALL)
        if not match:
            return None
        value = re.sub(r"\s+", " ", match.group(1)).strip()
        return html.unescape(value) if value else None

    def canonical_href() -> str | None:
        match = re.search(
            r'<link[^>]+rel=["\']canonical["\'][^>]+href=["\']([^"\']+)["\']',
            html_text,
            flags=re.IGNORECASE,
        )
        return html.unescape(match.group(1).strip()) if match else None

    def extract_channel_id(text: str | None) -> str | None:
        if not text:
            return None
        match = re.search(r"/channel/(UC[a-zA-Z0-9_-]{10,})", text)
        return match.group(1) if match else None

    og_title = meta_property("og:title")
    og_image = meta_property("og:image")
    og_url = meta_property("og:url")
    canonical = canonical_href()
    print(
        "resolve_channel_fast: found "
        f"og_url={'yes' if og_url else 'no'}, canonical={'yes' if canonical else 'no'}"
    )

    title = og_title or title_tag()
    if title:
        title = re.sub(r"\s*-\s*YouTube\s*$", "", title, flags=re.IGNORECASE).strip()

    channel_id = extract_channel_id(final_url)
    if not channel_id:
        channel_id = extract_channel_id(og_url)
    if not channel_id:
        channel_id = extract_channel_id(canonical)
    if not channel_id:
        channel_id = extract_channel_id(html_text)

    if not channel_id:
        raise ValueError(
            "Missing channel_id: "
            f"status_code={status_code}, "
            f"final_url={final_url}, "
            f"meta/canonical not found="
            f"{not og_url and not canonical}"
        )
    if not title:
        raise ValueError("Missing title")

    result = {
        "channel_id": channel_id,
        "title": title,
        "avatar_url": og_image,
        "source_url": source,
        "final_url": final_url,
    }
    print(f"resolve_channel_fast: done channel_id={channel_id}")
    return result


def resolve_channel(url_or_handle: str) -> dict:
    return resolve_channel_fast(url_or_handle)


def video_metadata(url: str) -> dict:
    try:
        import yt_dlp

        with yt_dlp.YoutubeDL({
            "quiet": True,
            "skip_download": True,
            "noplaylist": True,
        }) as ydl:
            info = ydl.extract_info(url, download=False)

        if not isinstance(info, dict):
            return {}

        return {
            "title": info.get("title"),
            "uploader": info.get("uploader") or info.get("channel"),
            "thumbnail": info.get("thumbnail"),
        }
    except Exception:
        return {}
