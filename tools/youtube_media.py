"""Download a public YouTube video with yt-dlp, validating media before completion.

Native yt-dlp is attempted first. On an HTTP transfer failure, the selected HTTPS
streams can be resumed with validated, fixed byte ranges and merged by ffmpeg.
No browser cookies, paid APIs, subtitles, or account credentials are used.
"""
from __future__ import annotations
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request

CHUNK = 1024 * 1024


def media_format(max_height=720):
    """Prefer Japanese audio on multilingual uploads, retaining unlabelled audio."""
    video = f'bv[height<={max_height}][ext=mp4][vcodec^=avc1]'
    return (video + '+ba[ext=m4a][language^=ja]/' + video + '+ba[ext=m4a]/'
            + f'b[height<={max_height}][ext=mp4][vcodec^=avc1]')


def download_ranges(url, headers, target, expected_size=None, deadline=None):
    """Persist only complete validated ranges; a failed request never adds bytes."""
    target = Path(target)
    offset = target.stat().st_size if target.exists() else 0
    total = expected_size
    if total is not None and offset > total:
        raise ValueError('Partial stream exceeds expected size')
    while total is None or offset < total:
        end = offset + CHUNK - 1
        if total is not None:
            end = min(end, total - 1)
        for attempt in range(3):
            if deadline is not None and time.monotonic() >= deadline:
                raise TimeoutError('Video download exceeded its time limit')
            timeout = min(20, max(.1, deadline-time.monotonic())) if deadline else 20
            request_headers = {**headers, 'Accept-Encoding': 'identity', 'Range': f'bytes={offset}-{end}'}
            try:
                request = urllib.request.Request(url, headers=request_headers)
                with urllib.request.urlopen(request, timeout=timeout) as response:
                    match = re.fullmatch(r'bytes (\d+)-(\d+)/(\d+)', response.headers.get('Content-Range', ''))
                    if response.status != 206 or not match:
                        raise ValueError('Server did not honor the requested byte range')
                    first, last, actual_total = map(int, match.groups())
                    if first != offset or last != min(end,actual_total-1) or actual_total <= last:
                        raise ValueError('Server returned a mismatched byte range')
                    if total is not None and actual_total != total:
                        raise ValueError('Stream size changed during download')
                    total = actual_total
                    data = response.read(last-first+2)
                    if len(data) != last-first+1:
                        raise IOError('Incomplete byte range')
                with target.open('ab') as stream:
                    stream.write(data)
                offset += len(data)
                break
            except urllib.error.HTTPError as error:
                complete = re.fullmatch(r'bytes \*/(\d+)',error.headers.get('Content-Range',''))
                if error.code==416 and complete and int(complete.group(1))==offset and (total is None or total==offset):
                    return offset
                if attempt==2:
                    raise
                time.sleep(min(2**attempt,max(0,deadline-time.monotonic())) if deadline else 2**attempt)
            except (urllib.error.URLError, TimeoutError, OSError):
                if attempt == 2:
                    raise
                time.sleep(min(2**attempt, max(0,deadline-time.monotonic())) if deadline else 2**attempt)
    return total


def probe(path, expected_duration=None, max_height=720):
    ffprobe = shutil.which('ffprobe')
    if not ffprobe:
        raise ValueError('ffprobe is required')
    result = subprocess.run([ffprobe,'-v','error','-show_entries',
        'format=duration:stream=codec_type,codec_name,height','-of','json',str(path)],
        capture_output=True,check=True,timeout=30)
    info = json.loads(result.stdout)
    video = next((s for s in info['streams'] if s['codec_type']=='video'),None)
    audio = next((s for s in info['streams'] if s['codec_type']=='audio'),None)
    if not video or not audio or video.get('codec_name')!='h264' or audio.get('codec_name')!='aac':
        raise ValueError('Expected browser-compatible H.264 video and AAC audio')
    if video.get('height',0)>max_height:
        raise ValueError('Downloaded video exceeds configured quality limit')
    duration = float(info['format']['duration'])
    if expected_duration is not None and abs(duration-expected_duration)>max(2,expected_duration*.01):
        raise ValueError('Downloaded duration does not match the source')
    return {'duration':duration,'height':video['height'],'video_codec':'h264','audio_codec':'aac'}


def download(url, output, max_height=720, timeout=900, js_runtime=None, cookies=None):
    import yt_dlp
    parsed = urllib.parse.urlparse(url)
    video_id = urllib.parse.parse_qs(parsed.query).get('v',[''])[0]
    if parsed.hostname not in ('www.youtube.com','youtube.com') or parsed.path!='/watch' or not re.fullmatch(r'[\w-]{11}',video_id):
        raise ValueError('Expected a normalized public YouTube watch URL')
    output = Path(output).resolve()
    if output.suffix.lower()!='.mp4':
        raise ValueError('Output must be an MP4 path')
    output.parent.mkdir(parents=True,exist_ok=True)
    work = output.parent/'.yt-work'/output.stem
    work.mkdir(parents=True,exist_ok=True)
    deadline = time.monotonic()+timeout
    opts = {'format':media_format(max_height),
            'outtmpl':str(work/'native.%(ext)s'),'noplaylist':True,'noprogress':True,
            'socket_timeout':20,'retries':2,'fragment_retries':2,'skip_unavailable_fragments':False,
            'http_chunk_size':CHUNK,'merge_output_format':'mp4','overwrites':False}
    if js_runtime:
        name,_,path=js_runtime.partition(':')
        opts['js_runtimes']={name:{'path':path} if path else {}}
    if cookies:
        # A cloud server is often asked to "confirm you're not a bot"; a signed-in cookies.txt gets past it.
        opts['cookiefile']=str(cookies)
    with yt_dlp.YoutubeDL(opts) as ydl:
        info=ydl.extract_info(url,download=False)
        expected=float(info['duration'])
        if info.get('id')!=video_id or info.get('is_live'):
            raise ValueError('Source identity mismatch or unsupported live stream')
        if output.exists():
            return probe(output,expected,max_height)
        native=work/'native.mp4'
        try:
            ydl.process_info(info)
            probe(native,expected,max_height)
            selected=native
        except (yt_dlp.utils.DownloadError,ValueError,subprocess.SubprocessError):
            print('[media] Native transfer failed; retrying the selected streams with validated byte ranges.',flush=True)
            formats=info.get('requested_formats') or [info]
            parts=[]
            for stream in formats:
                stream_url=stream.get('url','')
                host=urllib.parse.urlparse(stream_url).hostname or ''
                if stream.get('protocol')!='https' or not host.endswith('.googlevideo.com'):
                    raise ValueError('Range retry requires a public Google Video HTTPS stream')
                fmt=str(stream['format_id'])
                if not re.fullmatch(r'[\w.-]+',fmt) or stream['ext'] not in ('mp4','m4a'):
                    raise ValueError('Unexpected stream format identifier')
                part=work/f'range-{fmt}.{stream["ext"]}.part'
                for refresh in range(2):
                    try:
                        download_ranges(stream_url,stream.get('http_headers',info.get('http_headers',{})),
                                        part,stream.get('filesize'),deadline)
                        break
                    except urllib.error.HTTPError as error:
                        if error.code!=403 or refresh or time.monotonic()>=deadline:
                            raise
                        print('[media] Refreshing stream metadata after a transfer rejection.',flush=True)
                        fresh=ydl.extract_info(url,download=False)
                        replacement=next((f for f in fresh.get('formats',[]) if str(f['format_id'])==fmt),None)
                        if not replacement or fresh.get('id')!=video_id:
                            raise ValueError('Selected stream changed while refreshing metadata') from error
                        stream=replacement
                        stream_url=stream['url']
                        fresh_host=urllib.parse.urlparse(stream_url).hostname or ''
                        if stream.get('protocol')!='https' or not fresh_host.endswith('.googlevideo.com'):
                            raise ValueError('Refreshed stream is not a public Google Video HTTPS URL')
                parts.append(part)
            selected=work/'merged.mp4'
            command=[shutil.which('ffmpeg') or 'ffmpeg','-nostdin','-y','-v','error']
            for part in parts:command+=['-i',str(part)]
            if len(parts)==2:command+=['-map','0:v:0','-map','1:a:0']
            command+=['-c','copy','-movflags','+faststart',str(selected)]
            subprocess.run(command,check=True,timeout=max(1,deadline-time.monotonic()))
        result=probe(selected,expected,max_height)
        # Even native output gets faststart for seeking over the app's HTTP stream.
        ready=work/'ready.mp4'
        subprocess.run([shutil.which('ffmpeg') or 'ffmpeg','-nostdin','-y','-v','error','-i',str(selected),
                        '-c','copy','-movflags','+faststart',str(ready)],check=True,
                       timeout=max(1,deadline-time.monotonic()))
        probe(ready,expected,max_height)
        os.replace(ready,output)
        return result


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url',required=True)
    parser.add_argument('--output',required=True,type=Path)
    parser.add_argument('--max-height',type=int,choices=(360,480,720,1080),default=720)
    parser.add_argument('--timeout',type=int,default=900)
    parser.add_argument('--js-runtime')
    parser.add_argument('--cookies',help='Netscape cookies.txt of a signed-in YouTube account')
    args=parser.parse_args()
    result=download(args.url,args.output,args.max_height,args.timeout,args.js_runtime,args.cookies)
    print(json.dumps({'output':str(args.output.resolve()),**result}),flush=True)
