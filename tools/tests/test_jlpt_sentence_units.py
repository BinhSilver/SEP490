import sys
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from jlpt_sentence_units import sentence_units

class SentenceUnitsTest(unittest.TestCase):
    def test_two_spoken_sentences_have_two_rows(self):
        text = 'そうだね。体育館は予約できるから、室内でできるゲームに変えたらどうかな。'
        rows = sentence_units(text)
        self.assertEqual([s['text'] for s in rows], ['そうだね。', '体育館は予約できるから、室内でできるゲームに変えたらどうかな。'])
        self.assertEqual(''.join(s['sourceText'] for s in rows), text)

    def test_question_and_four_alternatives_are_separate(self):
        rows = sentence_units('何をしますか。１番、帰る。２番、買う。３番、行く。４番、待つ。')
        self.assertEqual(len(rows), 5)
        self.assertEqual([s['text'][:3] for s in rows[1:]], ['１番、', '２番、', '３番、', '４番、'])

    def test_quoted_alternative_can_have_multiple_sentences(self):
        text = '選んでください。1「わかりました。写真を撮りません。」 2「はい。」 3「いいえ。」'
        rows = sentence_units(text)
        self.assertEqual([s['text'] for s in rows], ['選んでください。', '1. わかりました。', '写真を撮りません。', '2. はい。', '3. いいえ。'])
        self.assertEqual(''.join(s['sourceText'] for s in rows).replace(' ', ''), text.replace(' ', ''))

    def test_option_labels_are_not_empty_vietnamese_rows(self):
        rows = sentence_units('1. Tôi hiểu rồi. 2. Xin cảm ơn. 3. Không, tôi không đi.', False)
        self.assertEqual([s['text'] for s in rows], ['1. Tôi hiểu rồi.', '2. Xin cảm ơn.', '3. Không, tôi không đi.'])

    def test_decimal_is_not_a_sentence_boundary(self):
        rows = sentence_units('Mất khoảng 1.5 giờ. Hẹn ngày mai.', False)
        self.assertEqual(len(rows), 2)
        self.assertEqual(rows[0]['text'], 'Mất khoảng 1.5 giờ.')

    def test_quotation_inside_a_sentence_keeps_its_context(self):
        rows = sentence_units('「待って。」と言われました。どうしますか。')
        self.assertEqual([s['text'] for s in rows], ['「待って。」と言われました。', 'どうしますか。'])

if __name__ == '__main__':
    unittest.main()
