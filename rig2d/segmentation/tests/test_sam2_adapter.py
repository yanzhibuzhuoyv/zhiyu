import unittest
import numpy as np
from segmentation.sam2_adapter import PromptRegion, PromptedSegmenter, SegmentationError, SegmentationCancelled


class FakePredictor:
    def __init__(self, outputs=None, fail_set=False, fail_predict=False):
        self.outputs = outputs
        self.fail_set = fail_set
        self.fail_predict = fail_predict
        self.calls = []
        self.resets = 0

    def set_image(self, image):
        self.image = image
        self.calls.append(('image', image.copy()))
        if self.fail_set: raise RuntimeError('image encoder failed')

    def predict(self, **kwargs):
        self.calls.append(('predict', kwargs))
        if self.fail_predict: raise RuntimeError('decoder failed')
        if self.outputs is not None: return self.outputs
        h, w = self.image.shape[:2]
        masks = np.zeros((3, h, w), np.float32)
        masks[:, 1:4, 1:4] = 1
        return masks, np.array([.85, .9, .89], np.float32), np.zeros((3, 256, 256), np.float32)

    def reset_predictor(self): self.resets += 1


class Sam2AdapterTest(unittest.TestCase):
    def image(self): return np.zeros((8, 11, 3), np.uint8)
    def region(self, id='face'): return PromptRegion(id, 'face', ((2, 2, 1),))

    def test_encodes_once_and_passes_xy_pixel_coordinates_without_double_normalization(self):
        predictor = FakePredictor()
        result = PromptedSegmenter(predictor).segment(self.image(), [self.region(), self.region('hair')])
        self.assertEqual(2, len(result.masks))
        self.assertEqual(1, sum(call[0] == 'image' for call in predictor.calls))
        self.assertEqual(1, predictor.resets)
        for _, args in predictor.calls[1:]:
            np.testing.assert_array_equal([[2, 2]], args['point_coords'])
            self.assertTrue(args['normalize_coords'])
            self.assertFalse(args['return_logits'])

    def test_float_binary_masks_and_best_quality_are_supported_without_array_alias(self):
        predictor = FakePredictor()
        result = PromptedSegmenter(predictor).segment(self.image(), [self.region()])
        chosen = result.masks[0]
        self.assertEqual(1, chosen.selected_index)
        self.assertTrue(chosen.accepted)
        self.assertEqual(9, sum(chosen.pixels))
        self.assertIsInstance(chosen.pixels, bytes)
        self.assertEqual((11, 8), (result.width, result.height))

    def test_prompt_consistency_prevents_higher_score_from_ignoring_a_positive_click(self):
        masks = np.zeros((3, 8, 11), np.float32)
        masks[0, 5:7, 5:7] = 1
        masks[1:, 1:4, 1:4] = 1
        output = (masks, np.array([.99, .91, .8]), np.zeros((3, 256, 256)))
        result = PromptedSegmenter(FakePredictor(output)).segment(self.image(), [self.region()])
        self.assertEqual(1, result.masks[0].selected_index)

    def test_negative_click_must_be_excluded_and_box_uses_xyxy(self):
        region = PromptRegion('face','face',((2,2,1),(6,5,0)),(1,1,4,4))
        predictor = FakePredictor()
        result = PromptedSegmenter(predictor).segment(self.image(), [region])
        self.assertTrue(result.masks[0].accepted)
        np.testing.assert_array_equal([1,1,4,4], predictor.calls[1][1]['box'])

    def test_empty_full_frame_and_low_quality_masks_are_explicitly_rejected(self):
        for masks, scores, reason in [(np.zeros((1,8,11)), [.9], 'empty_mask'),
                                      (np.ones((1,8,11)), [.9], 'full_frame_mask'),
                                      (np.pad(np.ones((1,3,3)),((0,0),(1,4),(1,7))), [.1], 'low_mask_quality')]:
            with self.subTest(reason=reason):
                result = PromptedSegmenter(FakePredictor((masks,np.array(scores),np.zeros((1,256,256))))).segment(self.image(), [self.region()])
                self.assertFalse(result.masks[0].accepted)
                self.assertIn(reason, result.masks[0].reasons)

    def test_invalid_prompts_are_rejected_before_any_model_call(self):
        invalid = [PromptRegion('../face','face',((2,2,1),)), PromptRegion('a','face',((11,2,1),)),
                   PromptRegion('a','face',((2,float('nan'),1),)), PromptRegion('a','face',((2,2,True),)),
                   PromptRegion('a','face',((2,2,0),)), PromptRegion('a','face',(),(5,1,2,4)),
                   PromptRegion('a','face',(),(0,0,11,7)), PromptRegion('a','face')]
        for region in invalid:
            predictor = FakePredictor()
            with self.subTest(region=region):
                with self.assertRaises(SegmentationError): PromptedSegmenter(predictor).segment(self.image(), [region])
                self.assertEqual([], predictor.calls)

    def test_invalid_frames_and_region_resource_bounds_are_rejected_before_encoding(self):
        for image in (np.zeros((0,11,3),np.uint8), np.zeros((8,11,4),np.uint8),
                      np.zeros((8,11,3),np.float32), np.zeros((1537,2,3),np.uint8)):
            with self.assertRaises(SegmentationError): PromptedSegmenter(FakePredictor()).segment(image, [self.region()])
        for regions in ([], [self.region(),self.region()], [self.region(str(i)) for i in range(64)]):
            with self.assertRaises(SegmentationError): PromptedSegmenter(FakePredictor()).segment(self.image(), regions)

    def test_malformed_model_shapes_scores_or_non_binary_pixels_are_not_publishable(self):
        normal=np.zeros((3,8,11)); normal[:,1:4,1:4]=1
        cases=[(normal,np.array([float('nan'),.9,.8]),np.zeros((3,256,256))),
               (normal,np.array([1.2,.9,.8]),np.zeros((3,256,256))),
               (np.zeros((4,8,11)),np.zeros(4),np.zeros((4,256,256))),
               (np.ones((3,7,11)),np.zeros(3),np.zeros((3,256,256))),
               (normal+.2,np.zeros(3),np.zeros((3,256,256))),
               (normal,np.zeros(3),np.full((3,256,256),float('inf')))]
        for output in cases:
            with self.subTest(shapes=[a.shape for a in output]):
                predictor=FakePredictor(output)
                with self.assertRaises(SegmentationError): PromptedSegmenter(predictor).segment(self.image(), [self.region()])
                self.assertEqual(1,predictor.resets)

    def test_encoder_and_decoder_failure_always_release_image_cache(self):
        for predictor in (FakePredictor(fail_set=True),FakePredictor(fail_predict=True)):
            with self.assertRaises(SegmentationError): PromptedSegmenter(predictor).segment(self.image(), [self.region()])
            self.assertEqual(1,predictor.resets)

    def test_cancelled_job_cannot_commit_results_even_after_decoder_finishes(self):
        cancelled=False
        class Cancelling(FakePredictor):
            def predict(self, **kwargs):
                nonlocal cancelled
                output=super().predict(**kwargs);cancelled=True;return output
        predictor=Cancelling()
        with self.assertRaises(SegmentationCancelled):
            PromptedSegmenter(predictor).segment(self.image(), [self.region()], lambda: cancelled)
        self.assertEqual(1,predictor.resets)

    def test_pixel_binding_is_deterministic_and_original_image_never_changes(self):
        image=self.image(); original=image.copy()
        first=PromptedSegmenter(FakePredictor()).segment(image, [self.region()])
        second=PromptedSegmenter(FakePredictor()).segment(image, [self.region()])
        self.assertEqual(first.source_pixel_sha256,second.source_pixel_sha256)
        np.testing.assert_array_equal(original,image)
        image[0,0,0]=1
        third=PromptedSegmenter(FakePredictor()).segment(image, [self.region()])
        self.assertNotEqual(first.source_pixel_sha256,third.source_pixel_sha256)

    def test_non_iterable_regions_produce_a_typed_input_error_before_encoding(self):
        for regions in (None, 7, 'face'):
            predictor=FakePredictor()
            with self.subTest(regions=regions):
                with self.assertRaises(SegmentationError):
                    PromptedSegmenter(predictor).segment(self.image(),regions)
                self.assertEqual([],predictor.calls)

    def test_reset_cannot_mutate_returned_masks_and_reset_failure_is_typed(self):
        masks=np.zeros((1,8,11)); masks[0,1:4,1:4]=1
        output=(masks,np.array([.9]),np.zeros((1,256,256)))
        class Modifying(FakePredictor):
            def reset_predictor(self):
                super().reset_predictor();masks.fill(0)
        result=PromptedSegmenter(Modifying(output)).segment(self.image(),[self.region()])
        self.assertEqual(9,sum(result.masks[0].pixels))
        class Broken(FakePredictor):
            def reset_predictor(self): raise RuntimeError('reset failed')
        with self.assertRaisesRegex(SegmentationError,'MODEL_RESET_FAILED'):
            PromptedSegmenter(Broken()).segment(self.image(),[self.region()])


if __name__ == '__main__': unittest.main()
