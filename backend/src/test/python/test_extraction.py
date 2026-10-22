import csv
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('formatter', Path(__file__).parents[2] / 'main/resources/extraction/format.py')
formatter = importlib.util.module_from_spec(spec)
spec.loader.exec_module(formatter)

class ExtractionTest(unittest.TestCase):
    def fixture(self, directory, rows, kind='DELIMITED', envelope=True, delimiter=' '):
        source, target = Path(directory)/'source.csv', Path(directory)/'output'
        entity = {'schema':'APP','name':'TEST','controlSumColumn':'ID','columns':[{'name':'ID','alias':'C0001','width':8,'numeric':True},{'name':'NAME','alias':'C0002','width':20,'numeric':False}]}
        with source.open('w',encoding='utf-8',newline='') as output:
            writer=csv.writer(output);writer.writerow(['C0001','C0002']);writer.writerows(rows)
        config={'format':kind,'envelope':envelope,'delimiter':delimiter,'systemCode':'PBI','countryCode':'SG'}
        formatter.extract(config,entity,source,target,'20261022')
        return target.read_text(encoding='utf-8').splitlines()

    def test_space_quoting_quotes_null_and_sum(self):
        with tempfile.TemporaryDirectory() as directory:
            rows=self.fixture(directory,[['V3245','VJOHN JAMES'],['V2','VJOHN "JJ"'],['V3','N'],['V4','V\\N']])
            self.assertEqual(rows[1:5],['D 3245 "JOHN JAMES"','D 2 "JOHN ""JJ"""','D 3 \\N','D 4 "\\N"'])
            self.assertTrue(rows[0].startswith('H PBI SG 20261022 '));self.assertEqual(rows[-1],'T 4 3254')

    def test_fixed_utf8_bytes_negative_padding_and_null(self):
        with tempfile.TemporaryDirectory() as directory:
            rows=self.fixture(directory,[['V-12.5','Vé'],['V1','N']],kind='FIXED')
            self.assertEqual(rows[1].encode('utf-8'),b'D-00012.5'+ 'é'.encode('utf-8')+b' '*19)
            self.assertEqual(len(rows[2].encode('utf-8')),29);self.assertEqual(len(rows[0]),63)

    def test_overflow_and_bad_rows_do_not_publish(self):
        for row in ([['V123456789','Vx']],[['V1','unexpected']],[['V1']]):
            with tempfile.TemporaryDirectory() as directory:
                with self.assertRaises(ValueError):self.fixture(directory,row,kind='FIXED')
                self.assertFalse((Path(directory)/'output').exists());self.assertFalse((Path(directory)/'output.part').exists())

    def test_empty_table_and_envelope_disabled(self):
        with tempfile.TemporaryDirectory() as directory:
            rows=self.fixture(directory,[]);self.assertEqual(rows[-1],'T 0 0')
        with tempfile.TemporaryDirectory() as directory:
            rows=self.fixture(directory,[['V1','Vcontains|pipe']],envelope=False,delimiter='|')
            self.assertEqual(rows,['1|"contains|pipe"'])

    def test_large_decimal_sum_and_many_rows(self):
        with tempfile.TemporaryDirectory() as directory:
            number='99999999999999999999999999999999999999'
            rows=self.fixture(directory,([('V'+number),'VName'] for _ in range(1001)))
            self.assertEqual(rows[-1],'T 1001 '+str(int(number)*1001));self.assertEqual(len(rows),1003)

    def test_large_clob_is_not_truncated_and_oversize_fails_without_publishing(self):
        body='large "quoted" body '+('x'*100000)
        with tempfile.TemporaryDirectory() as directory:
            rows=self.fixture(directory,[['V1','V'+body]])
            self.assertEqual(rows[1],'D 1 "'+body.replace('"','""')+'"')
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(csv.Error):self.fixture(directory,[['V1','V'+('x'*(16*1024*1024))]])
            self.assertFalse((Path(directory)/'output').exists());self.assertFalse((Path(directory)/'output.part').exists())

if __name__=='__main__':unittest.main()
