#!/usr/bin/env python3
"""
Скачивает Paper, WorldEdit и WorldGuard для интеграционного теста в CI.

Использование: download_server.py <версия MC | latest> <версия WorldGuard | latest> <папка>
"""
import io
import json
import re
import sys
import urllib.parse
import urllib.request
import zipfile

HEADERS = {"User-Agent": "WGRegionList-CI (https://github.com/Majorzxc/WGAddon)"}


def get(url):
    request = urllib.request.Request(url, headers=HEADERS)
    with urllib.request.urlopen(request, timeout=120) as response:
        return response.read()


def get_json(url):
    return json.loads(get(url))


def version_key(version):
    return tuple(int(part) for part in re.findall(r"\d+", version))


def is_plugin(data):
    try:
        names = zipfile.ZipFile(io.BytesIO(data)).namelist()
    except zipfile.BadZipFile:
        return False
    return "plugin.yml" in names or "paper-plugin.yml" in names


def paper_versions():
    try:
        data = get_json("https://fill.papermc.io/v3/projects/paper")
        versions = []
        for group in data["versions"].values():
            versions.extend(group)
        return versions
    except Exception as error:  # noqa: BLE001
        print(f"Fill v3 недоступен ({error}), пробую API v2")
        return get_json("https://api.papermc.io/v2/projects/paper")["versions"]


def paper_download(mc):
    try:
        build = get_json(f"https://fill.papermc.io/v3/projects/paper/versions/{mc}/builds/latest")
        return build["downloads"]["server:default"]["url"], build.get("id")
    except Exception as error:  # noqa: BLE001
        print(f"Fill v3 для {mc}: {error}")
    builds = get_json(f"https://api.papermc.io/v2/projects/paper/versions/{mc}/builds")["builds"]
    build = builds[-1]
    name = build["downloads"]["application"]["name"]
    return f"https://api.papermc.io/v2/projects/paper/versions/{mc}/builds/{build['build']}/downloads/{name}", build["build"]


def resolve_paper(mc):
    if mc != "latest":
        return mc, paper_download(mc)
    candidates = sorted((v for v in paper_versions() if re.fullmatch(r"[\d.]+", v)), key=version_key, reverse=True)
    for version in candidates:
        try:
            return version, paper_download(version)
        except Exception as error:  # noqa: BLE001
            print(f"Paper {version}: сборок нет ({error})")
    raise SystemExit("Не удалось найти сборку Paper")


def modrinth(slug, mc):
    for loaders in (["paper"], ["bukkit"], ["spigot"]):
        query = urllib.parse.urlencode({"game_versions": json.dumps([mc]), "loaders": json.dumps(loaders)})
        try:
            versions = get_json(f"https://api.modrinth.com/v2/project/{slug}/version?{query}")
        except Exception as error:  # noqa: BLE001
            print(f"Modrinth {slug}: {error}")
            return None
        releases = [v for v in versions if v.get("version_type") == "release"] or versions
        for version in releases:
            files = sorted(version["files"], key=lambda f: not f.get("primary"))
            for file in files:
                data = get(file["url"])
                if is_plugin(data):
                    print(f"{slug}: {version['version_number']} ({file['filename']}) — Modrinth")
                    return data
    return None


def enginehub(group, artifact, version):
    base = f"https://maven.enginehub.org/repo/{group}/{artifact}"
    if version == "latest":
        metadata = get(f"{base}/maven-metadata.xml").decode()
        version = re.search(r"<release>([^<]+)</release>", metadata).group(1)
    for suffix in ("-dist.jar", ".jar"):
        url = f"{base}/{version}/{artifact}-{version}{suffix}"
        try:
            data = get(url)
        except Exception as error:  # noqa: BLE001
            print(f"{url}: {error}")
            continue
        if is_plugin(data):
            print(f"{artifact}: {version} — {url}")
            return data
    return None


def main():
    mc, worldguard_version, target = sys.argv[1], sys.argv[2], sys.argv[3]
    mc, (paper_url, build) = resolve_paper(mc)
    print(f"Paper {mc} build {build}: {paper_url}")
    with open(f"{target}/paper.jar", "wb") as out:
        out.write(get(paper_url))

    worldedit = modrinth("worldedit", mc) or enginehub("com/sk89q/worldedit", "worldedit-bukkit", "latest")
    if worldedit is None:
        raise SystemExit("Не удалось скачать WorldEdit")
    with open(f"{target}/plugins/worldedit.jar", "wb") as out:
        out.write(worldedit)

    if worldguard_version == "latest":
        worldguard = modrinth("worldguard", mc) or enginehub("com/sk89q/worldguard", "worldguard-bukkit", "latest")
    else:
        worldguard = enginehub("com/sk89q/worldguard", "worldguard-bukkit", worldguard_version)
    if worldguard is None:
        raise SystemExit("Не удалось скачать WorldGuard")
    with open(f"{target}/plugins/worldguard.jar", "wb") as out:
        out.write(worldguard)

    with open(f"{target}/mc-version.txt", "w") as out:
        out.write(mc)


if __name__ == "__main__":
    main()
