import unittest
import locust_riemann_sender as lrs

all_stats = \
    {"errors": [],
     "stats": [
         {"median_response_time": 3200,
          "min_response_time": 3056,
          "current_rps": 0.3,
          "name": "/load",
          "num_failures": 0,
          "max_response_time": 3299,
          "avg_content_length": 114,
          "avg_response_time": 3155.68,
          "method": "GET",
          "num_requests": 101},
         {"median_response_time": 3200,
          "min_response_time": 3056,
          "current_rps": 0.3,
          "name": "Total",
          "num_failures": 0,
          "max_response_time": 3299,
          "avg_content_length": 114,
          "avg_response_time": 3155.68,
          "method": None,
          "num_requests": 101}],
     "state": "running",
     "total_rps": 0.3,
     "fail_ratio": 0.0,
     "user_count": 1}


class TestLocustRiemannSender(unittest.TestCase):

    def test_build_resource_events(self):
        events = lrs.build_resource_events('/no-such-resource', all_stats)
        self.assertEquals(0, len(events))

        stats = all_stats["stats"][0]
        events = lrs.build_resource_events('/load', all_stats)
        self.assertEquals(len(lrs.resource_metric_names[lrs.resource]), len(events))
        for mn in lrs.resource_metric_names[lrs.resource]:
            found = False
            for e in events:
                if e.get('service') == mn:
                    self.assertEquals(stats[mn], e.get('metric_f'))
                    self.assertEqual(lrs.host_name, e.get('host'))
                    self.assertEqual(lrs.tags, e.get('tags'))
                    found = True
            self.assertEquals(found, True, "Metric not found %s" % mn)

    def test_build_global_events(self):
        events = lrs.build_global_events({})
        self.assertEquals(0, len(events))

        events = lrs.build_global_events(all_stats)
        self.assertEquals(len(lrs.global_metric_names), len(events))
        for mn in lrs.global_metric_names:
            found = False
            for e in events:
                if e.get('service') == mn:
                    self.assertEquals(all_stats[mn], e.get('metric_f'))
                    self.assertEqual(lrs.host_name, e.get('host'))
                    self.assertEqual(lrs.tags, e.get('tags'))
                    found = True
            self.assertEquals(found, True, "Metric not found %s" % mn)


if __name__ == '__main__':
    unittest.main()
