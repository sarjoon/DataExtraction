#!/usr/bin/env python3
"""Streaming client-side formatter; Python standard library only."""
import csv
import datetime
import decimal
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile

csv.field_size_limit(16 * 1024 * 1024)

def field(value, delimiter):
    if value is None:
        return '\\N'
    if value == '\\N' or any(c in value for c in (delimiter, '"', '\r', '\n')):
        return '"' + value.replace('"', '""') + '"'
    return value

def fixed(value, width, numeric=False):
    value = '' if value is None else value
    size = len(value.encode('utf-8'))
    if size > width:
        raise ValueError('Value exceeds fixed width of %s UTF-8 bytes' % width)
    padding = width - size
    if numeric and value:
        return value[:1] + '0' * padding + value[1:] if value[:1] in ('-', '+') else '0' * padding + value
    return value + ' ' * padding

def extract(config, entity, source, target, business_date):
    datetime.datetime.strptime(business_date, '%Y%m%d')
    timestamp = datetime.datetime.now().strftime('%Y%m%d%H%M%S')
    columns = entity['columns']
    sum_index = next((i for i, c in enumerate(columns) if c['name'] == entity['controlSumColumn']), None)
    count, total = 0, decimal.Decimal(0)
    target = Path(target)
    temporary = target.with_name(target.name + '.part')
    delimiter, envelope = config['delimiter'], config['envelope']
    def record(kind, values, widths=None):
        if config['format'] == 'DELIMITED':
            return delimiter.join([kind] + [field(v, delimiter) for v in values])
        return kind + ''.join(fixed(v, w) for v, w in zip(values, widths))
    try:
        with open(source, encoding='utf-8-sig', newline='') as incoming, open(temporary, 'x', encoding='utf-8', newline='') as outgoing, decimal.localcontext() as context:
            context.prec = 1000
            if envelope:
                outgoing.write(record('H', [config['systemCode'], config['countryCode'], business_date, timestamp], [20, 20, 8, 14]) + '\n')
            reader = csv.reader(incoming, strict=True)
            first = True
            for row in reader:
                if first and row == [c['alias'] for c in columns]:
                    first = False
                    continue
                first = False
                if len(row) != len(columns):
                    raise ValueError('Unexpected CSV column count at source record %s' % (count + 1))
                values = []
                for raw in row:
                    if raw == 'N':
                        values.append(None)
                    elif raw.startswith('V'):
                        values.append(raw[1:])
                    else:
                        raise ValueError('Unexpected SQLcl output at source record %s; inspect the SQLcl log' % (count + 1))
                if sum_index is not None and values[sum_index] is not None:
                    number = decimal.Decimal(values[sum_index])
                    if not number.is_finite():
                        raise ValueError('Control sum contains a non-finite number')
                    total += number
                if config['format'] == 'DELIMITED':
                    data = delimiter.join(field(v, delimiter) for v in values)
                    if envelope:
                        data = 'D' + delimiter + data
                else:
                    data = ''.join(fixed(v, c['width'], c['numeric']) for v, c in zip(values, columns))
                    if envelope:
                        data = 'D' + data
                outgoing.write(data + '\n')
                count += 1
            if envelope:
                trailer = [str(count)] + ([format(total, 'f')] if sum_index is not None else [])
                outgoing.write(record('T', trailer, [15, 40]) + '\n')
            outgoing.flush()
            os.fsync(outgoing.fileno())
        os.replace(temporary, target)
    except BaseException:
        temporary.unlink(missing_ok=True)
        raise
    print('%s.%s: %s records written' % (entity['schema'], entity['name'], count))

def main():
    with open(sys.argv[2], encoding='utf-8') as stream:
        config = json.load(stream)
    if sys.argv[1] == 'list':
        for entity in config['entities']:
            print(entity['id'])
        return
    entity = next(e for e in config['entities'] if e['id'] == sys.argv[3])
    destination = Path(sys.argv[5]) / entity['filename']
    extract(config, entity, sys.argv[4], destination, sys.argv[6])
    if len(sys.argv) > 7 and sys.argv[7]:
        transfer = Path(sys.argv[7])
        transfer.mkdir(parents=True, exist_ok=True)
        fd, name = tempfile.mkstemp(prefix='.' + destination.name + '.', suffix='.part', dir=transfer)
        try:
            with os.fdopen(fd, 'wb') as output, open(destination, 'rb') as source:
                shutil.copyfileobj(source, output)
            os.replace(name, transfer / destination.name)
        finally:
            Path(name).unlink(missing_ok=True)

if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print('ERROR: ' + str(error), file=sys.stderr)
        sys.exit(1)
