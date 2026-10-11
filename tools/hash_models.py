#!/usr/bin/env python3
# 파일명: hash_models.py
# 목적: Phase 1 ASR·VAD 모델 artifact 를 실제로 내려받아 파일별 bytes·SHA-256 을 고정하고 앱 카탈로그(ModelCatalogData.kt)를 생성한다.
# 기능: tools/model_sources.json(사람이 관리하는 원본 목록)을 읽어
#       (1) 각 다운로드를 tools/models_cache/<id>/dl<n>/ 새 빈 폴더에 이어받기(Range) 방식으로 받고 크기·SHA-256 을 확인,
#       (2) 압축(tar.bz2/zip)은 tools/models_cache/<id>/x<n>/ 새 빈 폴더에 안전하게 해제(../·절대경로·링크·장치 파일 거부),
#       (3) 선언된 파일(path, role)의 bytes·SHA-256 을 계산하고 선언되지 않은 파일(테스트 음원·README·스크립트 등)을 제외 목록으로 출력,
#       (4) import 전용 항목(다운로드 없음)은 고정 해시를 쓰고 로컬 산출물이 있으면 해시를 다시 확인,
#       (5) --emit-kotlin 이면 app/.../model/ModelCatalogData.kt 를 한국어 헤더·항목 주석과 함께 생성한다.
#       이미 받아 검증된 캐시는 다시 받지 않는다(재실행 가능). 내려받은 내용은 실행하지 않고 읽기만 한다.
# 실행: python3 -I tools/hash_models.py [--list] [--only id1,id2] [--emit-kotlin]
#       (-I: 내려받은 폴더의 파일이 import 되지 않도록 격리 모드로 실행)
# change(add)-hyungchul-2026-10-10-16:12

import argparse  # 명령행 인자
import hashlib  # SHA-256
import json  # 원본 목록·잠금 파일
import shutil  # 폴더 삭제(캐시 재해제)
import sys  # 표준 출력·종료
import tarfile  # tar.bz2 해제
import time  # 재시도 대기
import urllib.error  # HTTP 오류
import urllib.request  # HTTPS 다운로드
import zipfile  # zip 해제
from pathlib import Path, PurePosixPath  # 경로 처리

ROOT = Path(__file__).resolve().parent.parent  # 프로젝트 루트
SOURCES = ROOT / "tools" / "model_sources.json"  # 사람이 관리하는 원본 목록
CACHE = ROOT / "tools" / "models_cache"  # 다운로드·해제 캐시(gitignore)
LOCK_OUT = CACHE / "catalog.lock.json"  # 계산 결과(디버그용, gitignore)
KOTLIN_OUT = ROOT / "app" / "src" / "main" / "java" / "com" / "cowork" / "stt_ondevice" / "model" / "ModelCatalogData.kt"  # 생성 대상
CHANGE_TAG = "change(add)-hyungchul-2026-10-10-16:12"  # 생성 파일에 넣을 변경 태그
CHUNK = 1 << 20  # 읽기/쓰기 단위 1 MiB
USER_AGENT = "stt-ondevice-hash-models/1.0"  # HTTP User-Agent
ARCHIVES = {"NONE", "TAR_BZ2", "ZIP"}  # 허용 압축 형식(Kotlin ArchiveType 과 같음)


def sha256_file(path: Path) -> tuple[int, str]:
    """
    목적: 파일 크기와 SHA-256 계산
    기능: 1 MiB 단위로 읽어 해시한다.
    입력: path(파일 경로)
    출력: 없음
    리턴: (바이트 수, 소문자 16진 SHA-256)
    """
    h = hashlib.sha256()  # 해시 객체
    n = 0  # 누적 바이트
    with path.open("rb") as f:  # 바이너리 읽기
        while True:  # 끝까지
            b = f.read(CHUNK)  # 1 MiB 읽기
            if not b:  # 파일 끝
                break  # 종료
            h.update(b)  # 해시 갱신
            n += len(b)  # 크기 누적
    return n, h.hexdigest()  # 결과


def http_download(url: str, part: Path, expect_bytes: int | None) -> int:
    """
    목적: HTTPS 이어받기 다운로드
    기능: part 파일이 있으면 Range 로 이어받는다. 서버가 Range 를 무시(200)하면 처음부터 다시 받는다.
          총 크기(Content-Range/Content-Length 또는 expect_bytes)와 최종 크기가 다르면 예외. 실패 시 최대 8회 재시도.
    입력: url(주소), part(임시 파일), expect_bytes(기대 크기, 모르면 None)
    출력: 크기 불일치·HTTP 오류 반복 시 RuntimeError
    리턴: 최종 바이트 수
    """
    if not url.startswith("https://"):  # HTTPS 만 허용
        raise RuntimeError(f"HTTPS 가 아닌 URL: {url}")  # 거부
    total = expect_bytes  # 총 크기(모르면 None)
    for attempt in range(8):  # 재시도 루프
        have = part.stat().st_size if part.exists() else 0  # 이미 받은 크기
        if total is not None and have == total:  # 이미 다 받음
            return have  # 완료
        if total is not None and have > total:  # 기대보다 큼 → 손상
            part.unlink()  # 버리고
            have = 0  # 처음부터
        req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})  # 요청
        if have > 0:  # 이어받기
            req.add_header("Range", f"bytes={have}-")  # 범위 요청
        try:  # 네트워크 오류 처리
            with urllib.request.urlopen(req, timeout=60) as resp:  # 연결(리다이렉트 자동)
                if have > 0 and resp.status == 206:  # 이어받기 수락
                    cr = resp.headers.get("Content-Range", "")  # "bytes a-b/total"
                    if "/" in cr and cr.rsplit("/", 1)[1].isdigit():  # 총 크기 표기 있음
                        total = int(cr.rsplit("/", 1)[1])  # 총 크기 갱신
                    mode = "ab"  # 덧붙이기
                else:  # 처음부터(200)
                    cl = resp.headers.get("Content-Length")  # 본문 크기
                    if cl is not None and cl.isdigit():  # 크기 표기 있음
                        total = int(cl)  # 총 크기
                    mode = "wb"  # 새로 쓰기
                    have = 0  # 처음부터
                with part.open(mode) as out:  # 임시 파일 열기
                    while True:  # 본문 끝까지
                        b = resp.read(CHUNK)  # 1 MiB 읽기
                        if not b:  # 끝
                            break  # 종료
                        out.write(b)  # 쓰기
            size = part.stat().st_size  # 현재 크기
            if total is None or size == total:  # 크기 확인 가능하면 일치해야 완료
                if expect_bytes is not None and size != expect_bytes:  # 기대 크기와 다름
                    raise RuntimeError(f"크기 불일치 {url}: {size} != 기대 {expect_bytes}")  # 실패
                return size  # 완료
            print(f"  [재시도] 크기 부족 {size}/{total}", flush=True)  # 중간 끊김
        except (urllib.error.URLError, TimeoutError, ConnectionError, OSError) as e:  # 네트워크 오류
            print(f"  [재시도 {attempt + 1}] {e}", flush=True)  # 로그
        time.sleep(min(30, 2 ** attempt))  # 지수 대기
    raise RuntimeError(f"다운로드 실패(재시도 초과): {url}")  # 포기


def url_basename(url: str) -> str:
    """
    목적: URL 에서 저장 파일 이름 결정
    기능: 쿼리를 떼고 마지막 경로 조각을 쓴다. 비거나 이상하면 예외.
    입력: url(주소)
    출력: 이름이 비거나 '..' 이면 RuntimeError
    리턴: 파일 이름
    """
    name = url.split("?", 1)[0].rstrip("/").rsplit("/", 1)[-1]  # 마지막 조각
    if not name or name in (".", "..") or "\\" in name:  # 잘못된 이름
        raise RuntimeError(f"URL 에서 파일 이름을 정할 수 없음: {url}")  # 거부
    return name  # 이름


def ensure_download(entry_id: str, idx: int, dl: dict) -> tuple[Path, int, str]:
    """
    목적: 다운로드 1건 확보(캐시 재사용)
    기능: tools/models_cache/<id>/dl<idx>/ 에 받는다. 표식 파일(dl<idx>.json)이 같은 URL·크기를 가리키면 다시 받지 않고 해시만 재확인한다.
          표식이 맞지 않거나 표식 없이 최종 파일만 있으면 폴더를 지우고 새 빈 폴더에 다시 받는다(.part 만 있으면 이어받기).
          expectBytes/expectSha256 이 있으면 반드시 일치해야 한다.
    입력: entry_id(모델 id), idx(다운로드 순번), dl(원본 목록의 download 항목)
    출력: 불일치 시 RuntimeError
    리턴: (파일 경로, 바이트 수, SHA-256)
    """
    ddir = CACHE / entry_id / f"dl{idx}"  # 이 다운로드 전용 폴더
    marker = CACHE / entry_id / f"dl{idx}.json"  # 검증 표식(폴더 밖)
    dest = ddir / url_basename(dl["url"])  # 최종 파일
    part = dest.with_name(dest.name + ".part")  # 임시 파일
    if marker.exists():  # 이전 실행 결과(표식 있음) change(add)-hyungchul-2026-10-10-20:00
        m = json.loads(marker.read_text(encoding="utf-8"))  # 표식 읽기
        if dest.exists() and m.get("url") == dl["url"] and m.get("bytes") == dest.stat().st_size:  # 같은 URL·크기 change(add)-hyungchul-2026-10-10-20:00
            n, sha = sha256_file(dest)  # 해시 재확인
            if sha == m.get("sha256"):  # 캐시 유효
                check_expect(dl, n, sha, dl["url"])  # 기대값 확인
                print(f"  [캐시] {dest.relative_to(ROOT)} {n} B", flush=True)  # 로그
                return dest, n, sha  # 재사용
        print(f"  [캐시 무효] {ddir.relative_to(ROOT)} 다시 받음", flush=True)  # 표식 불일치(URL 변경·파일 없음·해시 다름) change(add)-hyungchul-2026-10-10-20:00
        shutil.rmtree(ddir, ignore_errors=True)  # 이 다운로드 전용 캐시 폴더를 통째로 지우고 새 빈 폴더에 다시 받는다 change(add)-hyungchul-2026-10-10-20:00
        marker.unlink()  # 표식 제거
    elif dest.exists():  # 표식 없이 최종 파일만 있음(이름 변경 직후 중단) → 검증 안 된 상태 change(add)-hyungchul-2026-10-10-20:00
        print(f"  [캐시 무효] 표식 없음 {dest.relative_to(ROOT)} 다시 받음", flush=True)  # 로그 change(add)-hyungchul-2026-10-10-20:00
        shutil.rmtree(ddir)  # 새 빈 폴더에서 다시 받기 위해 삭제 change(add)-hyungchul-2026-10-10-20:00
    if not ddir.exists():  # 처음
        ddir.mkdir(parents=True)  # 새 빈 폴더
    print(f"  [다운로드] {dl['url']}", flush=True)  # 로그
    http_download(dl["url"], part, dl.get("expectBytes"))  # 받기(이어받기)
    part.rename(dest)  # 완료 → 최종 이름
    n, sha = sha256_file(dest)  # 해시
    check_expect(dl, n, sha, dl["url"])  # 기대값 확인
    marker.write_text(json.dumps({"url": dl["url"], "bytes": n, "sha256": sha}, indent=1), encoding="utf-8")  # 표식 저장
    print(f"  [완료] {n} B sha256={sha}", flush=True)  # 로그
    return dest, n, sha  # 결과


def check_expect(item: dict, n: int, sha: str, what: str) -> None:
    """
    목적: 기대 크기·해시 확인
    기능: item 에 expectBytes/expectSha256 이 있으면 계산값과 같아야 한다(공개 메타데이터·기존 골든 해시와 교차 확인).
    입력: item(원본 목록 항목), n(계산 크기), sha(계산 해시), what(오류 메시지용 이름)
    출력: 불일치 시 RuntimeError
    리턴: 없음
    """
    if "expectBytes" in item and item["expectBytes"] != n:  # 크기 다름
        raise RuntimeError(f"크기 불일치 {what}: {n} != 기대 {item['expectBytes']}")  # 실패
    if "expectSha256" in item and item["expectSha256"] != sha:  # 해시 다름
        raise RuntimeError(f"SHA-256 불일치 {what}: {sha} != 기대 {item['expectSha256']}")  # 실패


def safe_member_name(name: str) -> PurePosixPath:
    """
    목적: 압축 항목 이름 검사
    기능: 절대경로·드라이브·역슬래시·'..'·빈 이름을 거부하고 './' 접두를 정리한다.
    입력: name(압축 안 경로)
    출력: 위험한 이름이면 RuntimeError
    리턴: 정리된 상대 경로
    """
    if name.startswith("/") or "\\" in name or (len(name) > 1 and name[1] == ":"):  # 절대/윈도 경로
        raise RuntimeError(f"압축 안 위험한 경로(절대): {name}")  # 거부
    p = PurePosixPath(name)  # 경로 객체
    parts = [x for x in p.parts if x not in ("", ".")]  # '.' 제거
    if not parts or any(x == ".." for x in parts):  # 빈 이름 / 상위 탈출
        raise RuntimeError(f"압축 안 위험한 경로(..): {name}")  # 거부
    return PurePosixPath(*parts)  # 정리된 경로


def write_member(dest_root: Path, rel: PurePosixPath, src) -> None:
    """
    목적: 압축 항목 1개를 파일로 쓰기
    기능: dest_root 아래에만 쓰도록 경로를 다시 확인하고, 이미 있는 파일은 덮어쓰지 않는다(중복 항목 거부).
    입력: dest_root(해제 폴더), rel(정리된 상대 경로), src(읽기 스트림)
    출력: 경로 탈출·중복 시 RuntimeError
    리턴: 없음
    """
    out = (dest_root / Path(*rel.parts)).resolve()  # 최종 경로
    if dest_root.resolve() not in out.parents:  # 해제 폴더 밖
        raise RuntimeError(f"해제 경로 탈출: {rel}")  # 거부
    out.parent.mkdir(parents=True, exist_ok=True)  # 상위 폴더
    with out.open("xb") as f:  # 새 파일(이미 있으면 예외)
        shutil.copyfileobj(src, f, CHUNK)  # 복사


def safe_extract(archive: Path, kind: str, dest: Path) -> None:
    """
    목적: 압축 안전 해제
    기능: 새 빈 폴더 dest 에 일반 파일·폴더만 해제한다. 심볼릭/하드 링크·장치·FIFO 는 거부. 권한 비트는 복원하지 않는다.
    입력: archive(압축 파일), kind(TAR_BZ2|ZIP), dest(만들 해제 폴더, 없어야 함)
    출력: 위험 항목·형식 오류 시 RuntimeError
    리턴: 없음
    """
    dest.mkdir(parents=True)  # 새 빈 폴더(이미 있으면 예외)
    if kind == "TAR_BZ2":  # tar.bz2
        with tarfile.open(archive, mode="r:bz2") as tf:  # 순차 읽기
            for m in tf:  # 항목마다
                rel_name = m.name  # 원래 이름
                if m.isdir():  # 폴더
                    safe_member_name(rel_name)  # 이름 검사만(폴더는 파일 쓸 때 생성)
                    continue  # 다음
                if not m.isreg():  # 일반 파일이 아님(링크·장치 등)
                    raise RuntimeError(f"tar 안 허용하지 않는 항목(type={m.type!r}): {rel_name}")  # 거부
                rel = safe_member_name(rel_name)  # 이름 검사
                src = tf.extractfile(m)  # 내용 스트림
                write_member(dest, rel, src)  # 쓰기
    elif kind == "ZIP":  # zip
        with zipfile.ZipFile(archive) as zf:  # 열기
            for info in zf.infolist():  # 항목마다
                mode = (info.external_attr >> 16) & 0o170000  # 유닉스 파일 형식 비트
                if mode not in (0, 0o100000, 0o040000):  # 일반 파일/폴더/미표기 외(링크 등)
                    raise RuntimeError(f"zip 안 허용하지 않는 항목(mode={oct(mode)}): {info.filename}")  # 거부
                rel = safe_member_name(info.filename)  # 이름 검사
                if info.is_dir():  # 폴더
                    continue  # 파일 쓸 때 생성
                with zf.open(info) as src:  # 내용 스트림
                    write_member(dest, rel, src)  # 쓰기
    else:  # 그 외
        raise RuntimeError(f"지원하지 않는 압축 형식: {kind}")  # 거부


def ensure_extracted(entry_id: str, idx: int, archive: Path, archive_sha: str, kind: str) -> Path:
    """
    목적: 압축 해제 결과 확보(캐시 재사용)
    기능: 표식(x<idx>.json)의 압축 해시가 같으면 재사용, 아니면 기존 해제 폴더를 지우고 새 빈 폴더에 다시 해제한다.
    입력: entry_id(모델 id), idx(다운로드 순번), archive(압축 파일), archive_sha(압축 SHA-256), kind(압축 형식)
    출력: 해제 실패 시 RuntimeError
    리턴: 해제 폴더
    """
    xdir = CACHE / entry_id / f"x{idx}"  # 해제 폴더
    marker = CACHE / entry_id / f"x{idx}.json"  # 해제 표식
    if marker.exists() and xdir.is_dir():  # 이전 해제 결과
        if json.loads(marker.read_text(encoding="utf-8")).get("archiveSha256") == archive_sha:  # 같은 압축
            return xdir  # 재사용
    if xdir.exists():  # 오래된/중단된 해제
        shutil.rmtree(xdir)  # 우리 캐시 폴더만 삭제
    marker.unlink(missing_ok=True)  # 표식 제거
    print(f"  [해제] {archive.name} → {xdir.relative_to(ROOT)}", flush=True)  # 로그
    safe_extract(archive, kind, xdir)  # 안전 해제
    marker.write_text(json.dumps({"archiveSha256": archive_sha}), encoding="utf-8")  # 표식
    return xdir  # 결과


def list_files(root: Path) -> list[str]:
    """
    목적: 폴더 안 모든 파일의 상대 경로 목록
    기능: 하위 폴더까지 일반 파일만 posix 상대 경로로 정렬해 돌려준다.
    입력: root(폴더)
    출력: 없음
    리턴: 상대 경로 리스트
    """
    return sorted(p.relative_to(root).as_posix() for p in root.rglob("*") if p.is_file())  # 정렬 목록


def process_entry(e: dict, list_only: bool) -> dict:
    """
    목적: 모델 항목 1개 처리
    기능: 다운로드·해제 후 선언 파일의 bytes·SHA-256 을 계산하고, 선언되지 않은 파일을 제외 목록으로 만든다.
          NONE 다운로드는 saveAs 로 선언 파일과 연결한다. import 전용 항목은 고정 해시(그리고 로컬 산출물 재확인)를 쓴다.
    입력: e(원본 목록 항목), list_only(True 면 선언 검사 없이 해제 파일 목록만 출력)
    출력: 선언 파일 없음·불일치 시 RuntimeError
    리턴: 계산 결과 dict(downloads, files, excluded)
    """
    print(f"[{e['id']}]", flush=True)  # 항목 시작 로그
    downloads = []  # 결과 다운로드 목록
    sources: dict[str, Path] = {}  # 선언 경로 → 실제 파일
    roots: list[Path] = []  # 압축 해제 루트들
    for i, dl in enumerate(e.get("downloads", [])):  # 다운로드마다
        if dl["archive"] not in ARCHIVES:  # 형식 검사
            raise RuntimeError(f"{e['id']}: 알 수 없는 archive {dl['archive']}")  # 거부
        path, n, sha = ensure_download(e["id"], i, dl)  # 받기
        downloads.append({"url": dl["url"], "bytes": n, "sha256": sha, "archive": dl["archive"], "stripPrefix": dl.get("stripPrefix", "")})  # 기록
        if dl["archive"] == "NONE":  # 단일 파일
            sources[dl["saveAs"]] = path  # 설치 이름 → 파일
            continue  # 다음
        if dl.get("stripPrefix", "") and not dl["stripPrefix"].endswith("/"):  # 접두 형식
            raise RuntimeError(f"{e['id']}: stripPrefix 는 '/' 로 끝나야 함")  # 거부
        xdir = ensure_extracted(e["id"], i, path, sha, dl["archive"])  # 해제
        all_files = list_files(xdir)  # 전체 목록
        bad = [f for f in all_files if not f.startswith(dl.get("stripPrefix", ""))]  # 접두 밖 파일
        if bad:  # 접두가 틀림
            raise RuntimeError(f"{e['id']}: stripPrefix '{dl.get('stripPrefix')}' 밖 파일 {bad[:5]}")  # 실패
        roots.append(xdir / dl.get("stripPrefix", ""))  # 모델 루트
    if list_only:  # 목록 모드
        for r in roots:  # 루트마다
            for f in list_files(r):  # 파일마다
                print(f"    {(r / f).stat().st_size:>12}  {f}")  # 크기·경로
        return {}  # 결과 없음
    files = []  # 결과 파일 목록
    declared = set()  # 선언 경로
    for fd in e["files"]:  # 선언 파일마다
        p = fd["path"]  # 설치 폴더 기준 상대 경로
        safe_member_name(p)  # 경로 형식 검사
        if p in declared:  # 중복
            raise RuntimeError(f"{e['id']}: 파일 중복 선언 {p}")  # 실패
        declared.add(p)  # 기록
        if not fd.get("role"):  # 역할 필수
            raise RuntimeError(f"{e['id']}: role 없음 {p}")  # 실패
        if not e.get("downloads"):  # import 전용
            n, sha = fd["bytes"], fd["sha256"]  # 고정값
            local = e.get("importLocalDir")  # 로컬 산출물 폴더
            if local and (ROOT / local / p).is_file():  # 산출물이 있으면 재확인
                ln, lsha = sha256_file(ROOT / local / p)  # 실제 해시
                if (ln, lsha) != (n, sha):  # 다름
                    raise RuntimeError(f"{e['id']}: 로컬 산출물 해시 불일치 {p}")  # 실패
                print(f"  [import 확인] {local}/{p} 해시 일치", flush=True)  # 로그
            else:  # 산출물 없음
                print(f"  [import 고정값 사용] {p} (로컬 산출물 없음)", flush=True)  # 로그
        else:  # 다운로드 항목
            if p in sources:  # NONE 다운로드 파일
                src = sources[p]  # 실제 파일
            else:  # 압축 안 파일
                hits = [r / p for r in roots if (r / p).is_file()]  # 루트에서 찾기
                if len(hits) != 1:  # 없거나 여러 개
                    raise RuntimeError(f"{e['id']}: 선언 파일을 압축에서 찾을 수 없음(또는 중복) {p}")  # 실패
                src = hits[0]  # 실제 파일
            n, sha = sha256_file(src)  # 계산
        check_expect(fd, n, sha, f"{e['id']}/{p}")  # 교차 확인
        files.append({"path": p, "bytes": n, "sha256": sha, "role": fd["role"]})  # 기록
    missing_saveas = set(sources) - declared  # files 에 없는 NONE 다운로드
    if missing_saveas:  # 연결 안 됨
        raise RuntimeError(f"{e['id']}: saveAs 가 files 에 없음 {sorted(missing_saveas)}")  # 실패
    excluded = sorted({f for r in roots for f in list_files(r)} - declared)  # 제외 파일
    for x in excluded:  # 제외 로그
        print(f"  [제외] {x}", flush=True)  # 출력
    return {"downloads": downloads, "files": files, "excluded": excluded}  # 결과


def kstr(s: str) -> str:
    """
    목적: Kotlin 문자열 리터럴 만들기
    기능: 역슬래시·따옴표·달러 기호를 이스케이프한다.
    입력: s(문자열)
    출력: 없음
    리턴: 따옴표 포함 Kotlin 리터럴
    """
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$") + '"'  # 이스케이프


def engine_config_kotlin(ec: dict) -> str:
    """
    목적: engineConfig JSON → Kotlin 식
    기능: whisper/sherpa/vosk/vad 4 형식을 EngineConfig 생성식으로 바꾼다.
    입력: ec(engineConfig dict)
    출력: 알 수 없는 type 이면 RuntimeError
    리턴: Kotlin 식 문자열
    """
    t = ec["type"]  # 형식
    if t == "whisper":  # Whisper
        return f"WhisperConfig(beamSize = {int(ec['beamSize'])})"  # 빔 크기
    if t == "sherpa":  # sherpa-onnx
        return f"SherpaConfig(SherpaKind.{ec['kind']})"  # 종류
    if t == "vosk":  # Vosk
        return "VoskConfig"  # 단일 객체
    if t == "vad":  # VAD 모델
        return f"VadModelConfig(VadFamily.{ec['family']})"  # 계열
    raise RuntimeError(f"알 수 없는 engineConfig.type: {t}")  # 거부


def emit_kotlin(entries: list[dict], results: dict[str, dict], checked: str) -> str:
    """
    목적: ModelCatalogData.kt 본문 생성
    기능: 항목마다 한국어 주석(출처·라이선스 메모)과 줄별 주석이 달린 ModelSpec 생성식을 만든다.
    입력: entries(원본 목록 항목들), results(id → 계산 결과), checked(라이선스 확인일)
    출력: 없음
    리턴: Kotlin 소스 문자열
    """
    L = []  # 출력 줄
    L.append("// 파일명: ModelCatalogData.kt")  # 헤더
    L.append("// 목적: Phase 1 ASR·VAD 모델 카탈로그 데이터 (자동 생성 파일 — 직접 고치지 말 것)")  # 헤더
    L.append("// 기능: tools/hash_models.py --emit-kotlin 이 tools/model_sources.json(사람이 관리하는 원본 목록)과")  # 헤더
    L.append("//       실제 다운로드·압축 해제 결과(파일별 bytes·SHA-256)를 합쳐 만든다. 내용을 바꾸려면 원본 목록을 고치고 스크립트를 다시 실행한다.")  # 헤더
    L.append(f"//       라이선스 확인일 {checked}. 근거 URL 은 model/README_카탈로그.md 참고.")  # 헤더
    L.append("package com.cowork.stt_ondevice.model // 모델 메타 패키지")  # 패키지
    L.append("")  # 빈 줄
    L.append("import com.cowork.stt_ondevice.vad.VadFamily // VAD 계열(VAD 모델 항목)")  # import
    L.append("")  # 빈 줄
    L.append(f"// {CHANGE_TAG}")  # 변경 태그
    L.append("/**")  # KDoc
    L.append(" * 목적: 카탈로그 원본 데이터 보관")  # KDoc
    L.append(" * 기능: 모든 ModelSpec 을 원본 목록 순서대로 담는다. 조회는 ModelCatalog 를 쓴다.")  # KDoc
    L.append(" */")  # KDoc
    L.append("internal object ModelCatalogData {")  # 객체 시작
    L.append("    /** 목적: 전체 모델 목록 / 기능: ASR → VAD 순서의 고정 목록(파일 SHA-256 은 실제 다운로드로 계산한 값) */")  # 속성 주석
    L.append("    val all: List<ModelSpec> = listOf( // 전체 목록")  # 목록 시작
    for e in entries:  # 항목마다
        r = results[e["id"]]  # 계산 결과
        L.append(f"        // ── {e['id']}: {e.get('note', '')}")  # 항목 주석
        if r["excluded"]:  # 제외 파일 있음
            L.append(f"        //    압축 안 제외 파일 {len(r['excluded'])}개(테스트 음원·README·스크립트·미사용 정밀도 등)")  # 제외 수
        if not e.get("downloads"):  # import 전용
            L.append("        //    다운로드 없음 = import 전용(PC 에서 변환한 산출물을 adb/SAF 로 넣는다)")  # 설명
        L.append("        ModelSpec( // 모델 1개")  # 생성식
        L.append(f"            id = {kstr(e['id'])}, // 고유 id")  # id
        L.append(f"            kind = ModelKind.{e['kind']}, // ASR/VAD")  # kind
        L.append(f"            algo = {kstr(e['algo'])}, // 결과 폴더 algo")  # algo
        L.append(f"            model = {kstr(e['model'])}, // 결과 폴더 model")  # model
        L.append(f"            displayName = {kstr(e['displayName'])}, // 화면 표시 이름")  # displayName
        langs = ", ".join(kstr(x) for x in e["languages"])  # 언어 목록
        L.append(f"            languages = {'setOf(' + langs + ')' if langs else 'emptySet()'}, // 지원 언어(VAD 는 언어 무관 = 빈 집합)")  # languages
        L.append(f"            runtime = Runtime.{e['runtime']}, // 실행 런타임")  # runtime
        L.append(f"            runtimeVersion = {kstr(e['runtimeVersion'])}, // 런타임 버전")  # runtimeVersion
        L.append(f"            precision = {kstr(e['precision'])}, // 가중치 정밀도(파일 이름 기준)")  # precision
        L.append(f"            maxChunkSec = {float(e['maxChunkSec'])!r}, // 1회 입력 최대 길이(초, 0 = 제한 없음/스트리밍)")  # maxChunkSec
        L.append(f"            streaming = StreamingKind.{e['streaming']}, // 파일 단위/네이티브 스트리밍")  # streaming
        if r["downloads"]:  # 다운로드 있음
            L.append("            downloads = listOf( // 내려받을 artifact")  # 목록 시작
            for d in r["downloads"]:  # 다운로드마다
                L.append(f"                Download({kstr(d['url'])}, {d['bytes']}L, {kstr(d['sha256'])}, ArchiveType.{d['archive']}, {kstr(d['stripPrefix'])}), // 압축 {d['archive']}")  # 항목
            L.append("            ),")  # 목록 끝
        else:  # import 전용
            L.append("            downloads = emptyList(), // import 전용")  # 빈 목록
        L.append("            files = listOf( // 설치 후 검사할 파일(경로는 모델 폴더 기준)")  # 목록 시작
        for f in r["files"]:  # 파일마다
            L.append(f"                ModelFile({kstr(f['path'])}, {f['bytes']}L, {kstr(f['sha256'])}, {kstr(f['role'])}), // 역할 {f['role']}")  # 항목
        L.append("            ),")  # 목록 끝
        L.append(f"            originalRepo = {kstr(e['originalRepo'])}, // 원본 저장소")  # originalRepo
        L.append(f"            license = {kstr(e['license'])}, // 라이선스")  # license
        L.append(f"            licenseUrl = {kstr(e['licenseUrl'])}, // 라이선스 근거 URL")  # licenseUrl
        L.append(f"            commercial = CommercialStatus.{e['commercial']}, // 상업 이용 판정")  # commercial
        ca = kstr(e["compatAlias"]) if e.get("compatAlias") else "null"  # PC 폴더 별칭
        L.append(f"            compatAlias = {ca}, // PC 평가기 폴더 별칭(없으면 null)")  # compatAlias
        L.append(f"            engineConfig = {engine_config_kotlin(e['engineConfig'])}, // 엔진 설정")  # engineConfig
        L.append("        ),")  # 생성식 끝
    L.append("    )")  # 목록 끝
    L.append("}")  # 객체 끝
    return "\n".join(L) + "\n"  # 본문


def validate_entry(e: dict) -> None:
    """
    목적: 원본 목록 항목의 필수 키 확인
    기능: Kotlin 생성에 필요한 키가 모두 있는지, NONE 다운로드에 saveAs 가 있는지 본다.
    입력: e(원본 목록 항목)
    출력: 빠진 키가 있으면 RuntimeError
    리턴: 없음
    """
    need = ["id", "kind", "algo", "model", "displayName", "languages", "runtime", "runtimeVersion", "precision", "maxChunkSec",
            "streaming", "files", "originalRepo", "license", "licenseUrl", "commercial", "engineConfig"]  # 필수 키
    miss = [k for k in need if k not in e]  # 빠진 키
    if miss:  # 있으면
        raise RuntimeError(f"{e.get('id')}: 필수 키 없음 {miss}")  # 실패
    for dl in e.get("downloads", []):  # 다운로드마다
        if dl["archive"] == "NONE" and not dl.get("saveAs"):  # 단일 파일인데 설치 이름 없음
            raise RuntimeError(f"{e['id']}: NONE 다운로드에 saveAs 필요")  # 실패


def main() -> int:
    """
    목적: 진입점
    기능: 인자를 읽고 항목을 순서대로 처리한 뒤 잠금 파일·Kotlin 파일을 쓴다.
    입력: 명령행(--list, --only, --emit-kotlin)
    출력: 진행 로그
    리턴: 종료 코드(0 성공)
    """
    ap = argparse.ArgumentParser(description="모델 artifact 다운로드·해시 고정·카탈로그 생성")  # 인자 정의
    ap.add_argument("--list", action="store_true", help="받고 해제한 뒤 파일 목록만 출력(files 선언 전 확인용)")  # 목록 모드
    ap.add_argument("--only", default="", help="쉼표로 구분한 id 만 처리")  # 일부만
    ap.add_argument("--emit-kotlin", action="store_true", help="ModelCatalogData.kt 생성(전체 항목 필요)")  # 생성
    args = ap.parse_args()  # 파싱
    src = json.loads(SOURCES.read_text(encoding="utf-8"))  # 원본 목록
    entries = src["models"]  # 항목들
    ids = [e["id"] for e in entries]  # id 목록
    if len(ids) != len(set(ids)):  # 중복 id
        raise RuntimeError("model_sources.json 에 중복 id")  # 실패
    only = {x for x in args.only.split(",") if x}  # 일부 처리 id
    unknown = only - set(ids)  # 없는 id
    if unknown:  # 있으면
        raise RuntimeError(f"알 수 없는 id: {sorted(unknown)}")  # 실패
    if args.emit_kotlin and (only or args.list):  # 생성은 전체 검증 필요
        raise RuntimeError("--emit-kotlin 은 --only/--list 없이 전체로 실행해야 함")  # 실패
    CACHE.mkdir(exist_ok=True)  # 캐시 루트
    results = {}  # id → 결과
    for e in entries:  # 항목마다
        if only and e["id"] not in only:  # 대상 아님
            continue  # 건너뜀
        validate_entry(e)  # 키 확인
        results[e["id"]] = process_entry(e, args.list)  # 처리
    if args.list:  # 목록 모드는 여기서 끝
        return 0  # 성공
    LOCK_OUT.write_text(json.dumps(results, indent=1, ensure_ascii=False), encoding="utf-8")  # 계산 결과 저장
    print(f"[잠금] {LOCK_OUT.relative_to(ROOT)}", flush=True)  # 로그
    if args.emit_kotlin:  # 생성
        KOTLIN_OUT.write_text(emit_kotlin(entries, results, src["checkedDate"]), encoding="utf-8")  # 쓰기
        print(f"[생성] {KOTLIN_OUT.relative_to(ROOT)}", flush=True)  # 로그
    return 0  # 성공


if __name__ == "__main__":  # 직접 실행
    sys.exit(main())  # 종료 코드
