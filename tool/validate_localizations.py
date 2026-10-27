#!/usr/bin/env python3
"""Check native catalogs without requiring Xcode or Android resource compilation."""
import json
import re
from collections import Counter
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
LANGUAGES = ('en', 'zh-Hans', 'zh-Hant', 'de', 'ru', 'pl', 'es-MX', 'fr', 'pt-BR', 'es', 'ja', 'ko')
AUTONYMS = {
    'language_english': 'English', 'language_simplified_chinese': '中文（简体）',
    'language_traditional_chinese': '中文（繁體）', 'language_german': 'Deutsch',
    'language_russian': 'Русский', 'language_polish': 'Polski',
    'language_spanish_mexico': 'Español (México)', 'language_french': 'Français',
    'language_portuguese_brazil': 'Português (Brasil)', 'language_spanish': 'Español',
    'language_japanese': '日本語', 'language_korean': '한국어',
}
# A numeric argument must keep its width and precision, even if reordered.
FORMAT = re.compile(r'%(?:(\d+)\$)?([-+ #0]*)(\d+)?(\.\d+)?(ll|l)?([@sdfiu])')


def signature(value):
    args = []
    position = 0
    for match in FORMAT.finditer(value.replace('%%', '')):
        index, flags, width, precision, length, kind = match.groups()
        position += 1
        args.append((int(index) if index else position, kind, length or '', width or '', precision or ''))
    return Counter(args)


def units(localization):
    if 'stringUnit' in localization:
        return {'value': localization['stringUnit']['value']}
    return {quantity: unit['stringUnit']['value']
            for quantity, unit in localization['variations']['plural'].items()}


def required_plurals(language):
    return {'one', 'few', 'many', 'other'} if language in ('ru', 'pl') else {'other'}


def validate():
    errors = []
    checked = 0

    def check(condition, message):
        if not condition:
            errors.append(message)

    for filename in ('Localizable.xcstrings', 'InfoPlist.xcstrings'):
        catalog = json.loads((ROOT / 'ios/NativeApp' / filename).read_text())['strings']
        if filename == 'Localizable.xcstrings':
            check(AUTONYMS.keys() <= catalog.keys(), f'{filename}: missing language title key')
        for key, entry in catalog.items():
            localizations = entry.get('localizations', {})
            baseline = units(localizations['en']) if 'en' in localizations else {'value': key}
            expected = signature(next(iter(baseline.values())))
            for language in LANGUAGES:
                prefix = f'{filename}:{key}:{language}'
                check(language in localizations, f'{prefix}: missing locale')
                if language not in localizations:
                    continue
                translated = units(localizations[language])
                if 'other' in baseline:
                    check(required_plurals(language) <= translated.keys(), f'{prefix}: missing plural category')
                for quantity, value in translated.items():
                    checked += 1
                    check(bool(value.strip()), f'{prefix}:{quantity}: empty text')
                    check(signature(value) == expected, f'{prefix}:{quantity}: format arguments changed')
                    if key in AUTONYMS:
                        check(value == AUTONYMS[key], f'{prefix}: autonym changed')

    resources = ROOT / 'android/app/src/main/res'
    base = {e.attrib['name']: e for e in ET.parse(resources / 'values/strings.xml').getroot()}
    check(AUTONYMS.keys() <= base.keys(), 'Android: missing language title key')
    for language in LANGUAGES:
        candidates = ['values' if language == 'en' else 'values-b+' + language.replace('-', '+')]
        if language not in ('en', 'zh-Hans', 'zh-Hant'):
            parts = language.split('-')
            candidates += ['values-' + parts[0] + ('-r' + parts[1] if len(parts) > 1 else '')]
        files = [resources / folder / 'strings.xml' for folder in candidates]
        path = next((p for p in files if p.exists()), None)
        check(path is not None, f'Android:{language}: missing resource file')
        if path is None:
            continue
        elements = list(ET.parse(path).getroot())
        translated = {e.attrib['name']: e for e in elements}
        check(len(translated) == len(elements), f'{path}: duplicate key')
        check(translated.keys() == base.keys(), f'{path}: keys differ: {base.keys() ^ translated.keys()}')
        for key, source in base.items():
            if key not in translated:
                continue
            target = translated[key]
            prefix = f'Android:{language}:{key}'
            check(target.tag == source.tag, f'{prefix}: changed resource type')
            source_value = source.text if source.tag == 'string' else source[0].text
            expected = signature(source_value or '')
            if target.tag == 'plurals':
                values = {e.attrib['quantity']: e.text or '' for e in target}
                check(required_plurals(language) <= values.keys(), f'{prefix}: missing plural category')
            else:
                values = {'value': target.text or ''}
            for quantity, value in values.items():
                checked += 1
                check(bool(value.strip()), f'{prefix}:{quantity}: empty text')
                check(signature(value) == expected, f'{prefix}:{quantity}: format arguments changed')
                # AAPT treats unescaped ASCII apostrophes/quotes as syntax.
                check(re.search(r"(?<!\\)['\"]", value) is None, f'{prefix}:{quantity}: unescaped Android quote')
                if key in AUTONYMS:
                    check(value == AUTONYMS[key], f'{prefix}: autonym changed')

    config = ET.parse(resources / 'xml/locales_config.xml').getroot()
    tags = [e.attrib['{http://schemas.android.com/apk/res/android}name'] for e in config]
    check(set(tags) == set(LANGUAGES), f'Android locale config differs: {tags}')
    if errors:
        raise SystemExit('\n'.join(errors))
    print(f'Validated {checked} native strings/plural forms across {len(LANGUAGES)} locales.')


if __name__ == '__main__':
    validate()
