#!/usr/bin/env python3
"""Render actual Minecraft recordings; trim only no-input gaps between logged action batches.

Windows Python, local ffmpeg/ffprobe. Never generates/replaces game frames or pointer motion.
The raw video, timing files and OS-input log remain alongside the edited review artifacts.
"""
import argparse
import json
from pathlib import Path
import shutil
import subprocess
import textwrap

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / 'runs/walkthrough'
REVIEW = WORK / 'review'
FFMPEG = shutil.which('ffmpeg')
FFPROBE = shutil.which('ffprobe')


def run(args):
    result = subprocess.run([FFMPEG, '-hide_banner', '-loglevel', 'error', '-y', *map(str,args)], cwd=WORK, capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError(result.stderr[-2500:])


def probe(path):
    result = subprocess.run([FFPROBE, '-v','error','-show_format','-show_streams','-of','json',str(path)],capture_output=True,text=True,check=True)
    return json.loads(result.stdout)


def timestamp(seconds):
    cs = round(seconds * 100)
    return f'{cs//360000}:{cs//6000%60:02}:{cs//100%60:02}.{cs%100:02}'


def ass_file(path, cues, duration):
    header = '''[Script Info]
ScriptType: v4.00+
PlayResX: 1280
PlayResY: 800
WrapStyle: 0
[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Caption,Segoe UI,24,&H00FFFFFF,&H00FFFFFF,&H00151A1F,&H00151A1F,0,0,0,0,100,100,0,0,1,0,0,2,32,32,18,1
[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
'''
    lines=[]
    for i,(start,text) in enumerate(cues):
        end=cues[i+1][0] if i+1<len(cues) else duration
        content=r'\N'.join(textwrap.wrap(text,94))
        lines.append(f'Dialogue: 0,{timestamp(start)},{timestamp(end)},Caption,,0,0,0,,{content}')
    path.write_text(header+'\n'.join(lines)+'\n',encoding='utf8')


def render(name, raw_name, prefix):
    raw=WORK/(raw_name+'.mp4')
    duration=float(probe(raw)['format']['duration'])
    origin=json.loads((WORK/(raw_name+'.timing.json')).read_text())['start_epoch_ms']
    events=[json.loads(line) for line in (WORK/'input-timeline.jsonl').read_text(encoding='utf-8-sig').splitlines() if line.strip()]
    events=[e for e in events if e.get('batch','').startswith(prefix) and 0 <= (e['at']-origin)/1000 < duration]
    groups={}
    for event in events:groups.setdefault(event['batch'],[]).append(event)
    ranges=[]
    for batch,items in groups.items():
        last=items[-1]['action']
        tail=last.get('after',0)/1000
        if last['action']=='pause':tail+=last.get('duration',0)/1000
        if last['action'] in ('move','click'):tail+=last.get('duration',750)/1000
        start=max(0,(items[0]['at']-origin)/1000-0.1)
        end=min(duration,(items[-1]['at']-origin)/1000+tail+0.1)
        ranges.append((start,end,batch))
    ranges.sort()
    if not ranges:raise ValueError('No matching real input batches for '+name)
    # Adjacent batches remain continuous; remove only long review gaps with no input.
    merged=[]
    for start,end,batch in ranges:
        if merged and start<=merged[-1][1]+1:
            merged[-1][1]=max(merged[-1][1],end);merged[-1][2].append(batch)
        else:merged.append([start,end,[batch]])
    segments=WORK/(name+'-segments');segments.mkdir(exist_ok=True)
    manifest=[];cues=[];out_time=0;cuts=[]
    for i,(start,end,batches) in enumerate(merged):
        target=segments/f'{i:03}.mp4'
        run(['-ss',f'{start:.3f}','-i',raw,'-t',f'{end-start:.3f}','-vf','fps=30','-an','-c:v','libx264','-preset','veryfast','-crf','17','-threads','2',target])
        span=float(probe(target)['format']['duration'])
        manifest.append("file '"+target.as_posix()+"'")
        for event in events:
            t=(event['at']-origin)/1000
            if start<=t<=end and event['action']['action']=='cue':cues.append((out_time+max(0,t-start),event['action']['value']))
        cuts.append({'source_start':start,'source_end':end,'output_start':out_time,'output_duration':span,'batches':batches})
        out_time+=span
    if cues:cues[0]=(0,cues[0][1])
    listing=segments/'concat.txt';listing.write_text('\n'.join(manifest)+'\n',encoding='utf8')
    joined=WORK/(name+'-edited.mp4');run(['-f','concat','-safe','0','-i',listing,'-c','copy',joined])
    captions=WORK/(name+'-captions.ass');ass_file(captions,cues,out_time)
    output=REVIEW/(name+'.mp4')
    run(['-i',joined,'-vf',f'pad=1280:800:0:0:color=0x151a1f,ass={captions.name}','-an','-c:v','libx264','-preset','veryfast','-crf','18','-threads','2','-movflags','+faststart',output])
    report={'file':output.name,'duration':float(probe(output)['format']['duration']),'source':raw.name,'edits':'Only no-input review gaps between logged batches removed. All shown input remains real-time. Captions occupy added band below game. No synthetic cursor or game frames.','cuts':cuts,'captions':[{'at':t,'text':s} for t,s in cues]}
    (REVIEW/(name+'.json')).write_text(json.dumps(report,indent=2)+'\n')
    print(name,round(report['duration'],1),'seconds',output.stat().st_size,'bytes',flush=True)
    return report


def combine():
    sources=[REVIEW/'branch-editor.mp4',REVIEW/'visual-delivery.mp4']
    listing=WORK/'combined-concat.txt';listing.write_text('\n'.join("file '"+p.as_posix()+"'" for p in sources)+'\n')
    first=round(float(probe(sources[0])['format']['duration'])*1000);total=first+round(float(probe(sources[1])['format']['duration'])*1000)
    chapters=WORK/'chapters.txt';chapters.write_text(f';FFMETADATA1\n[CHAPTER]\nTIMEBASE=1/1000\nSTART=0\nEND={first}\ntitle=Branch editor: creation, choices, save and play\n[CHAPTER]\nTIMEBASE=1/1000\nSTART={first}\nEND={total}\ntitle=Delivery: tags, item properties, save and submission\n')
    output=REVIEW/'full-walkthrough.mp4';run(['-f','concat','-safe','0','-i',listing,'-i',chapters,'-map_metadata','1','-map_chapters','1','-c','copy','-movflags','+faststart',output]);print('Combined walkthrough:',round(total/1000,1),'seconds',flush=True)


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('chapter',choices=['branch','delivery','combine','all']);args=parser.parse_args()
    if not FFMPEG or not FFPROBE:parser.error('Run with Windows Python where ffmpeg and ffprobe are installed')
    REVIEW.mkdir(parents=True,exist_ok=True)
    if args.chapter in ('branch','all'):render('branch-editor','branch-walkthrough-raw','film-')
    if args.chapter in ('delivery','all'):render('visual-delivery','delivery-walkthrough-raw','delivery-')
    if args.chapter in ('combine','all'):combine()


if __name__=='__main__':main()
