import unittest
from verify_final import compatibility_failed


class ExperimentalDirtyFlashTests(unittest.TestCase):
    def receipt(self):
        return dict(donor_failures=[], consumer_failures=[], flag_provider_consumers=[],
                    mapper_callsite_candidates=[{'status': 'unreviewed-call-sites', 'sha256': 'unknown'}])

    def test_default_rejects_unknown(self):
        self.assertTrue(compatibility_failed(self.receipt()))

    def test_explicit_experiment_defers_only_unknown(self):
        self.assertFalse(compatibility_failed(self.receipt(), True))

    def test_each_mechanical_failure_still_rejects(self):
        for category in ('donor_failures', 'consumer_failures', 'flag_provider_consumers'):
            with self.subTest(category=category):
                receipt = self.receipt()
                receipt[category] = [{'failure': 'measured'}]
                self.assertTrue(compatibility_failed(receipt, True))

    def test_reviewed_binding_drift_still_rejects(self):
        receipt = self.receipt()
        receipt['mapper_callsite_candidates'][0]['review_binding_rejection'] = {'error': 'changed provider'}
        self.assertTrue(compatibility_failed(receipt, True))
