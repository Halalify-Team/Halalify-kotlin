from __future__ import annotations

from pathlib import Path
import xml.etree.ElementTree as ET

import numpy as np
from PIL import Image
from manim import *


ROOT = Path(__file__).resolve().parent


def find_file(filename: str, folders: tuple[str, ...] = ("", "assets", "Image")) -> Path:
	for folder in folders:
		candidate = ROOT / folder / filename
		if candidate.exists():
			return candidate
	raise FileNotFoundError(filename)


def build_body_without_face() -> Path:
	source = find_file("body.svg", ("", "assets", "assets/low byte"))
	generated = ROOT / "_generated" / "body_without_face.svg"
	generated.parent.mkdir(exist_ok=True)
	if generated.exists() and generated.stat().st_mtime >= source.stat().st_mtime:
		return generated

	tree = ET.parse(source)
	root = tree.getroot()
	for parent in root.iter():
		for child in list(parent):
			attributes = child.attrib
			path_data = attributes.get("d", "")
			remove = (
				path_data.startswith("M448.5 484")
				or path_data.startswith("M452.538 347.5")
				or path_data.startswith("M599.538 347.5")
				or attributes.get("cy") == "390"
				and attributes.get("cx") in {"452", "599"}
			)
			if remove:
				parent.remove(child)
	tree.write(generated, encoding="utf-8", xml_declaration=True)
	return generated


def build_pixelated_woman() -> Path:
    source = find_file("woman.png", ("", "assets", "Image"))
    generated = ROOT / "_generated" / "woman_pixelated.png"
    if generated.exists() and generated.stat().st_mtime >= source.stat().st_mtime:
        return generated
    generated.parent.mkdir(exist_ok=True)
    with Image.open(source) as image:
        image = image.convert("RGB")
        pixel_width = 8
        pixel_height = max(1, round(image.height / image.width * pixel_width))
        pixelated = image.resize((pixel_width, pixel_height), Image.Resampling.BOX)
        pixelated = pixelated.resize(image.size, Image.Resampling.NEAREST)
        pixelated.save(generated)
    return generated


def normalized(vector: np.ndarray, fallback: np.ndarray = RIGHT) -> np.ndarray:
	value = np.array(vector, dtype=float)
	value[2] = 0
	length = np.linalg.norm(value[:2])
	return value / length if length > 1e-8 else np.array(fallback, dtype=float)


class CharacterRig(VGroup):
	def __init__(self, canvas_height: float = 3.45, **kwargs):
		super().__init__(**kwargs)
		self.canvas_height = canvas_height
		self.canvas_origin = ORIGIN.copy()
		folders = ("", "assets", "assets/low byte")
		self.body = self._svg_parts(build_body_without_face())
		left_eye = self._svg_parts(find_file("left_eye.svg", folders))
		right_eye = self._svg_parts(find_file("right_eye.svg", folders))
		left_pupil = self._svg_parts(find_file("left_pupil.svg", folders))
		right_pupil = self._svg_parts(find_file("right_pupil.svg", folders))
		mouth = self._svg_parts(find_file("mouth.svg", folders))
		self.left_eye_white, self.right_eye_white = left_eye[0], right_eye[0]
		self.left_pupil, self.right_pupil = left_pupil[0], right_pupil[0]
		self.mouth = mouth[0]
		for eye, pupil in ((self.left_eye_white, self.left_pupil), (self.right_eye_white, self.right_pupil)):
			eye.scale(1.45, about_point=eye.get_center())
			pupil.scale(1.45, about_point=eye.get_center())
		self.mouth.scale(1.4, about_point=self.mouth.get_center())
		self.left_pupil.saved_state = self.left_pupil.copy()
		self.right_pupil.saved_state = self.right_pupil.copy()
		right_arm = VGroup(
			self._svg_parts(find_file("right_upper_arm.svg", folders)),
			self._svg_parts(find_file("right_forearm.svg", folders)),
			self._svg_parts(find_file("right_hand.svg", folders)),
		)
		self.right_arm = right_arm
		self.add(right_arm, self.body, self.left_eye_white, self.right_eye_white,
				 self.left_pupil, self.right_pupil, self.mouth)

	def _svg_parts(self, path: Path) -> VGroup:
		svg = SVGMobject(path)
		visible = [part for part in svg.submobjects if not (part.width > 1.9 and part.height > 1.9)]
		layer = VGroup(*visible)
		layer.scale(self.canvas_height / 2, about_point=ORIGIN)
		return layer

	def place_canvas_at(self, point: np.ndarray) -> "CharacterRig":
		self.shift(np.array(point) - self.canvas_origin)
		self.canvas_origin = np.array(point, dtype=float)
		self.left_pupil.saved_state.become(self.left_pupil)
		self.right_pupil.saved_state.become(self.right_pupil)
		return self

	def asset_point(self, x: float, y: float) -> np.ndarray:
		return self.canvas_origin + np.array(((x - 500) * self.canvas_height / 1000,
											  (500 - y) * self.canvas_height / 1000, 0))

	def prepare_gaze_to(self, target: np.ndarray, max_distance: float = 0.055) -> list[Animation]:
		animations = []
		for pupil, eye in ((self.left_pupil, self.left_eye_white), (self.right_pupil, self.right_eye_white)):
			direction = normalized(np.array(target) - eye.get_center())
			pupil.target = pupil.saved_state.copy().shift(direction * max_distance)
			animations.append(MoveToTarget(pupil))
		return animations

	def blink(self, scene: Scene, run_time: float = 0.08) -> None:
		scene.play(*[eye.animate.stretch(0.08, dim=1, about_point=eye.get_center())
					 for eye in (self.left_eye_white, self.right_eye_white)],
				   run_time=run_time * 2, rate_func=there_and_back)


class SVGArmRig(VGroup):
	def __init__(self, upper: VGroup, forearm: VGroup, hand: VGroup,
				 shoulder: np.ndarray, elbow: np.ndarray, wrist: np.ndarray, **kwargs):
		super().__init__(**kwargs)
		self.upper, self.forearm, self.hand = upper, forearm, hand
		self.shoulder, self.elbow, self.wrist = map(lambda value: np.array(value, dtype=float), (shoulder, elbow, wrist))
		self.upper_rest, self.forearm_rest, self.hand_rest = upper.copy(), forearm.copy(), hand.copy()
		self.shoulder_angle = ValueTracker(0)
		self.elbow_angle = ValueTracker(0)
		self.wrist_angle = ValueTracker(0)
		self.visibility = ValueTracker(0)
		self.add(upper, forearm, hand)
		self.add_updater(self._update_pose)

	@staticmethod
	def _rotate(point: np.ndarray, pivot: np.ndarray, angle: float) -> np.ndarray:
		delta = point - pivot
		cosine, sine = np.cos(angle), np.sin(angle)
		return pivot + np.array((delta[0] * cosine - delta[1] * sine,
								 delta[0] * sine + delta[1] * cosine, delta[2]))

	def pose_animations(self, shoulder: float, elbow: float, wrist: float) -> tuple[Animation, ...]:
		return (self.shoulder_angle.animate.set_value(shoulder),
				self.elbow_angle.animate.set_value(elbow), self.wrist_angle.animate.set_value(wrist))

	def _update_pose(self, _mob: Mobject) -> None:
		shoulder_angle = self.shoulder_angle.get_value()
		elbow_angle = self.elbow_angle.get_value()
		wrist_angle = self.wrist_angle.get_value()
		upper = self.upper_rest.copy().rotate(shoulder_angle, about_point=self.shoulder)
		moved_elbow = self._rotate(self.elbow, self.shoulder, shoulder_angle)
		forearm = self.forearm_rest.copy().rotate(shoulder_angle, about_point=self.shoulder)
		forearm.rotate(elbow_angle, about_point=moved_elbow)
		moved_wrist = self._rotate(self.wrist, self.shoulder, shoulder_angle)
		moved_wrist = self._rotate(moved_wrist, moved_elbow, elbow_angle)
		hand = self.hand_rest.copy().rotate(shoulder_angle, about_point=self.shoulder)
		hand.rotate(elbow_angle, about_point=moved_elbow)
		hand.rotate(wrist_angle, about_point=moved_wrist)
		self.upper.become(upper).set_opacity(self.visibility.get_value())
		self.forearm.become(forearm).set_opacity(self.visibility.get_value())
		self.hand.become(hand).set_opacity(self.visibility.get_value())


class OpenSourceScreenGuardScene(Scene):
    def _style_arm(self, upper, forearm, hand):
        for layer in (upper, forearm):
            for part in layer:
                part.set_stroke("#6F9E22", width=7.5, opacity=1)
        for part in hand:
            part.set_fill("#6F9E22", opacity=1).set_stroke("#6F9E22", width=7.5, opacity=1)

    def _arm(self, character, prefix, shoulder_xy, elbow_xy, wrist_xy, scale):
        folders = ("", "assets", "assets/low byte")
        upper = character._svg_parts(find_file(f"{prefix}_upper_arm.svg", folders)).shift(character.canvas_origin)
        forearm = character._svg_parts(find_file(f"{prefix}_forearm.svg", folders)).shift(character.canvas_origin)
        hand = character._svg_parts(find_file(f"{prefix}_hand.svg", folders)).shift(character.canvas_origin)
        shoulder = character.asset_point(*shoulder_xy)
        elbow = character.asset_point(*elbow_xy)
        wrist = character.asset_point(*wrist_xy)
        for layer in (upper, forearm, hand):
            layer.scale(scale, about_point=shoulder)
        elbow = shoulder + (elbow - shoulder) * scale
        wrist = shoulder + (wrist - shoulder) * scale
        self._style_arm(upper, forearm, hand)
        return SVGArmRig(upper, forearm, hand, shoulder, elbow, wrist)

    @staticmethod
    def _mouth(center, points):
        mouth = VMobject(color=BLACK, stroke_width=7)
        mouth.set_points_smoothly([center + point for point in points])
        return mouth

    def construct(self):
        self.camera.background_color = BLACK
        CYAN, GREEN, RED = "#43E6D0", "#55D98B", "#FF6B6B"
        YELLOW, BLUE, PURPLE = "#F6C85F", "#60A5FA", "#A78BFA"
        PANEL, PANEL2, LINE = "#081018", "#101923", "#33475B"
        SOFT, MUTED = "#E5EEF8", "#90A2B8"

        # Character: every body/arm/hand/face part comes from the SVG assets.
        character = CharacterRig(3.45).place_canvas_at(LEFT * 4.35 + DOWN * 0.72)
        character.right_arm.set_opacity(0)
        character.left_pupil.save_state()
        character.right_pupil.save_state()
        character.body.set_z_index(2)
        character.left_eye_white.set_z_index(5)
        character.right_eye_white.set_z_index(5)
        character.left_pupil.set_z_index(6)
        character.right_pupil.set_z_index(6)
        character.mouth.set_z_index(6)

        pointing_arm = self._arm(character, "left", (669, 378), (734, 407), (748, 462), 2.15)
        resting_arm = self._arm(character, "right", (369, 373), (324, 378), (308, 439), 1.55)
        pointing_arm.set_z_index(4)
        resting_arm.set_z_index(3)
        resting_arm.shoulder_angle.set_value(0.16)
        resting_arm.elbow_angle.set_value(-0.34)
        resting_arm.wrist_angle.set_value(-0.04)

        mouth_center = character.mouth.get_center()
        neutral = self._mouth(mouth_center, [LEFT * 0.14, DOWN * 0.01, RIGHT * 0.14])
        thinking = self._mouth(mouth_center, [LEFT * 0.14, LEFT * 0.03 + UP * 0.03, RIGHT * 0.14])
        worried = self._mouth(mouth_center, [LEFT * 0.14 + UP * 0.01, DOWN * 0.05, RIGHT * 0.14 + UP * 0.01])
        smile = self._mouth(mouth_center, [LEFT * 0.15, DOWN * 0.035, RIGHT * 0.15])
        confident = self._mouth(mouth_center, [LEFT * 0.14, LEFT * 0.04 + UP * 0.018, RIGHT * 0.14])
        character.mouth.become(neutral)

        self.add(resting_arm, pointing_arm)
        self.play(
            FadeIn(character, shift=UP * 0.10),
            pointing_arm.visibility.animate.set_value(1),
            resting_arm.visibility.animate.set_value(1),
            run_time=0.70,
        )

        # “I decided to solve the problem” — short idea beat.
        idea = VGroup(
            Circle(radius=0.17, stroke_color=YELLOW, stroke_width=2.4),
            VGroup(*[
                Line(UP * 0.25, UP * 0.40, color=YELLOW, stroke_width=2.5).rotate(i * PI / 4)
                for i in range(8)
            ]),
        ).move_to(LEFT * 1.85 + UP * 2.35)
        self.play(
            *character.prepare_gaze_to(idea.get_center()),
            Transform(character.mouth, thinking),
            FadeIn(idea, scale=0.65),
            *pointing_arm.pose_animations(0.18, -0.38, 0.08),
            run_time=0.55,
        )
        self.play(idea.animate.scale(1.15), run_time=0.28, rate_func=there_and_back)

        # Phone app.
        phone = RoundedRectangle(
            width=2.78, height=5.46, corner_radius=0.30,
            stroke_color=CYAN, stroke_width=2.7,
            fill_color="#071018", fill_opacity=1,
        ).move_to(LEFT * 0.20 + DOWN * 0.02)
        screen = RoundedRectangle(
            width=2.38, height=4.88, corner_radius=0.18,
            stroke_width=0, fill_color=PANEL2, fill_opacity=1,
        ).move_to(phone)
        notch = RoundedRectangle(
            width=0.82, height=0.16, corner_radius=0.06,
            stroke_width=0, fill_color="#020406", fill_opacity=1,
        ).move_to(phone.get_top() + DOWN * 0.20)
        home = RoundedRectangle(
            width=0.80, height=0.055, corner_radius=0.025,
            stroke_width=0, fill_color="#243446", fill_opacity=1,
        ).move_to(phone.get_bottom() + UP * 0.19)

        header = RoundedRectangle(
            width=2.10, height=0.48, corner_radius=0.13,
            fill_color=PANEL, fill_opacity=1, stroke_color=LINE, stroke_width=1,
        ).move_to(screen.get_top() + DOWN * 0.43)
        app_dot = Dot(radius=0.05, color=CYAN).move_to(header.get_left() + RIGHT * 0.18)
        app_title = Text("HALAL GUARD", font="Consolas", font_size=16, color=SOFT, weight=BOLD).move_to(header)
        live = RoundedRectangle(
            width=0.46, height=0.20, corner_radius=0.07,
            fill_color="#0D2227", fill_opacity=1, stroke_color=CYAN, stroke_width=1,
        ).move_to(header.get_right() + LEFT * 0.27)
        live_text = Text("LIVE", font="Consolas", font_size=9.5, color=CYAN, weight=BOLD).move_to(live)

        status = RoundedRectangle(
            width=1.58, height=0.28, corner_radius=0.09,
            fill_color="#0D2227", fill_opacity=1, stroke_color=CYAN, stroke_width=1,
        ).move_to(header.get_bottom() + DOWN * 0.24)
        status_text = Text("Monitoring screen", font="Consolas", font_size=10, color=CYAN).move_to(status)

        def ui_lines(width1, width2):
            return VGroup(
                Line(LEFT * width1 / 2, RIGHT * width1 / 2, color=SOFT, stroke_width=2.2),
                Line(LEFT * width2 / 2, RIGHT * width2 / 2, color=MUTED, stroke_width=2.2),
            ).arrange(DOWN, buff=0.10, aligned_edge=LEFT)

        # Social post with an image that is blurred when detected.
        post = RoundedRectangle(
            width=2.08, height=2.12, corner_radius=0.14,
            fill_color=PANEL, fill_opacity=1, stroke_color=LINE, stroke_width=1,
        )
        avatar = Circle(radius=0.07, fill_color=BLUE, fill_opacity=1, stroke_width=0)
        post_name = Text("social feed", font="Consolas", font_size=11, color=SOFT)
        post_head = VGroup(avatar, post_name).arrange(RIGHT, buff=0.10)
        post_head.move_to(post.get_top() + DOWN * 0.18 + LEFT * 0.50)
        photo = ImageMobject(find_file("woman.png", ("", "assets", "Image")))
        photo.set_width(1.80).set_height(1.08).move_to(post.get_center() + UP * 0.18)
        photo_frame = RoundedRectangle(
            width=1.80, height=1.08, corner_radius=0.10,
            fill_opacity=0, stroke_color="#42576B", stroke_width=1,
        ).move_to(photo)
        post_copy = ui_lines(1.04, 0.72).move_to(post.get_bottom() + UP * 0.27 + LEFT * 0.18)
        post_group = Group(post, post_head, photo, photo_frame, post_copy)

        normal_card = RoundedRectangle(
            width=2.08, height=0.68, corner_radius=0.14,
            fill_color=PANEL, fill_opacity=1, stroke_color=LINE, stroke_width=1,
        )
        normal_icon = Circle(radius=0.09, fill_color=GREEN, fill_opacity=0.18, stroke_color=GREEN, stroke_width=1.2)
        normal_icon.move_to(normal_card.get_left() + RIGHT * 0.20)
        normal_lines = ui_lines(0.92, 0.56).move_to(normal_card.get_center() + RIGHT * 0.17)
        normal_group = VGroup(normal_card, normal_icon, normal_lines)

        music_card = RoundedRectangle(
            width=2.08, height=0.82, corner_radius=0.14,
            fill_color=PANEL, fill_opacity=1, stroke_color=LINE, stroke_width=1,
        )
        note = Text("♪", font_size=34, color=YELLOW).move_to(music_card.get_left() + RIGHT * 0.25)
        song_copy = ui_lines(0.66, 0.42).move_to(music_card.get_center() + LEFT * 0.10)
        waves = VGroup(*[
            Line(DOWN * h / 2, UP * h / 2, color=YELLOW, stroke_width=3)
            for h in (0.16, 0.38, 0.56, 0.30, 0.44)
        ]).arrange(RIGHT, buff=0.06).move_to(music_card.get_right() + LEFT * 0.34)
        music_group = VGroup(music_card, note, song_copy, waves)

        feed = Group(post_group, normal_group, music_group).arrange(DOWN, buff=0.16)
        feed.move_to(screen.get_center() + DOWN * 0.18)
        scroll_track = RoundedRectangle(
            width=0.05, height=2.72, corner_radius=0.02,
            stroke_width=0, fill_color="#203042", fill_opacity=0.55,
        ).move_to(screen.get_right() + LEFT * 0.09)
        scroll_thumb = RoundedRectangle(
            width=0.05, height=0.54, corner_radius=0.02,
            stroke_width=0, fill_color=CYAN, fill_opacity=0.95,
        ).move_to(scroll_track.get_top() + DOWN * 0.40)

        phone_group = Group(
            phone, screen, notch, home, header, app_dot, app_title, live, live_text,
            status, status_text, feed, scroll_track, scroll_thumb,
        )

        self.play(
            FadeOut(idea, scale=0.75),
            FadeIn(phone_group, shift=UP * 0.10),
            *character.prepare_gaze_to(phone.get_center()),
            Transform(character.mouth, confident),
            *pointing_arm.pose_animations(0.76, -0.62, 0.24),
            run_time=0.78,
        )

        # Browse like a real app: scroll down, small bounce, eyes follow content.
        self.play(
            feed.animate.shift(UP * 0.58),
            scroll_thumb.animate.shift(DOWN * 0.36),
            *character.prepare_gaze_to(screen.get_center() + DOWN * 0.15),
            run_time=0.90,
            rate_func=smooth,
        )
        self.play(
            feed.animate.shift(DOWN * 0.16),
            scroll_thumb.animate.shift(UP * 0.10),
            run_time=0.32,
            rate_func=rate_functions.ease_out_cubic,
        )
        character.blink(self)

        # App detects the image and blocks it.
        scan = Line(photo.get_left(), photo.get_right(), color=CYAN, stroke_width=4)
        scan.move_to(photo.get_top() + DOWN * 0.12)
        blurred_photo = ImageMobject(build_pixelated_woman())
        blurred_photo.set_width(1.80).set_height(1.08).move_to(photo)
        blur_cover = RoundedRectangle(
            width=1.80, height=1.08, corner_radius=0.10,
            fill_color="#05080C", fill_opacity=0.45, stroke_width=0,
        ).move_to(photo).set_opacity(0)
        blocked_status = status.copy().set_fill("#251114", opacity=1).set_stroke(RED, width=1)
        blocked_text = Text("Sensitive image blocked", font="Consolas", font_size=9.5, color=RED).move_to(blocked_status)

        self.play(
            *character.prepare_gaze_to(photo.get_center()),
            Transform(character.mouth, worried),
            FadeIn(scan),
            run_time=0.23,
        )
        self.play(scan.animate.move_to(photo.get_bottom() + UP * 0.12), run_time=0.78, rate_func=linear)
        self.play(
            Transform(photo, blurred_photo), FadeIn(blur_cover),
            Transform(status, blocked_status), Transform(status_text, blocked_text),
            run_time=0.42,
        )

        # Music is detected and muted.
        mute_x = VGroup(
            Line(UL * 0.18, DR * 0.18, color=RED, stroke_width=3.8),
            Line(UR * 0.18, DL * 0.18, color=RED, stroke_width=3.8),
        ).move_to(music_card.get_right() + LEFT * 0.22)
        muted_status = status.copy().set_fill("#10211A", opacity=1).set_stroke(GREEN, width=1)
        muted_text = Text("Music muted", font="Consolas", font_size=10, color=GREEN).move_to(muted_status)
        self.play(
            FadeIn(mute_x),
            waves.animate.set_opacity(0.15),
            Transform(status, muted_status), Transform(status_text, muted_text),
            *character.prepare_gaze_to(music_card.get_center()),
            run_time=0.45,
        )
        self.play(Transform(character.mouth, smile), run_time=0.28)

        self.remove(scan, mute_x)
        phone_group.add(scan, blur_cover, mute_x)

        # Open-source code appears while the app stays visible.
        code_panel = RoundedRectangle(
            width=4.55, height=4.05, corner_radius=0.22,
            fill_color=PANEL, fill_opacity=1, stroke_color=GREEN, stroke_width=2,
        ).move_to(RIGHT * 4.20 + UP * 0.08)
        code_title = Text("OPEN SOURCE", font="Consolas", font_size=24, color=GREEN, weight=BOLD)
        code_title.next_to(code_panel.get_top(), DOWN, buff=0.27)
        public = RoundedRectangle(
            width=1.25, height=0.26, corner_radius=0.09,
            fill_color="#10211A", fill_opacity=1, stroke_color=GREEN, stroke_width=1,
        ).next_to(code_title, DOWN, buff=0.16).align_to(code_title, LEFT)
        public_text = Text("PUBLIC REPO", font="Consolas", font_size=10, color=GREEN, weight=BOLD).move_to(public)
        code = Code(
            code_string=(
                "class ScreenGuard:\n"
                "    def scan(self, frame):\n"
                "        if block_image(frame):\n"
                "            frame = mask(frame)\n"
                "        if music_detected():\n"
                "            mute()\n"
                "        return frame"
            ),
            language="python", background="window", formatter_style="monokai",
        ).scale(0.58).move_to(code_panel.get_center() + DOWN * 0.02)
        contributors = VGroup(*[
            Circle(radius=0.12, fill_color=color, fill_opacity=1, stroke_width=0)
            for color in (CYAN, BLUE, PURPLE, YELLOW, GREEN)
        ]).arrange(RIGHT, buff=0.12).move_to(code_panel.get_bottom() + UP * 0.38)
        plus = Text("+", font_size=25, color=GREEN).next_to(contributors, RIGHT, buff=0.14)
        contribute_text = Text("CONTRIBUTE", font="Consolas", font_size=11, color=SOFT, weight=BOLD)
        contribute_text.next_to(contributors, LEFT, buff=0.18)

        self.play(
            phone_group.animate.shift(LEFT * 0.72).scale(0.97),
            FadeIn(code_panel), FadeIn(code_title), FadeIn(public), FadeIn(public_text), FadeIn(code),
            run_time=0.75,
        )
        self.play(
            *character.prepare_gaze_to(code_panel.get_center()),
            Transform(character.mouth, confident),
            *pointing_arm.pose_animations(0.52, -0.86, 0.30),
            FadeIn(contribute_text), FadeIn(contributors), FadeIn(plus),
            run_time=0.70,
        )
        self.play(Indicate(code_title, color=GREEN), Indicate(contributors, color=GREEN), run_time=0.55)
        character.blink(self)
        self.wait(3.5)


class HalalImageModerationScene(Scene):
    def construct(self):
        self.camera.background_color = BLACK
        CYAN, BLUE, GREEN, RED = "#43E6D0", "#60A5FA", "#55D98B", "#FF6B6B"
        PANEL = "#081018"

        photo_frame = RoundedRectangle(
            width=3.48, height=2.48, corner_radius=0.16,
            stroke_color=CYAN, stroke_width=2, fill_color=PANEL, fill_opacity=1,
        )
        original_photo = ImageMobject(find_file("woman.png", ("", "assets", "Image")))
        original_photo.set_width(3.20).set_height(2.12).move_to(photo_frame)
        blurred_photo = ImageMobject(build_pixelated_woman())
        blurred_photo.set_width(3.20).set_height(2.12).move_to(photo_frame)
        blur_cover = Rectangle(
            width=3.20, height=2.12, stroke_width=0,
            fill_color="#05080C", fill_opacity=0.46,
        ).move_to(photo_frame).set_opacity(0)
        blur_pixels = VGroup(*[
            Rectangle(
                width=0.22, height=2.04, stroke_width=0,
                fill_color=color, fill_opacity=opacity,
            ).move_to(photo_frame.get_left() + RIGHT * (0.18 + index * 0.25))
            for index, (color, opacity) in enumerate(
                ((CYAN, 0.10), (BLUE, 0.13), (GREEN, 0.08), (RED, 0.10),
                 (BLUE, 0.14), (CYAN, 0.08), (GREEN, 0.12), (BLUE, 0.10),
                 (RED, 0.08), (CYAN, 0.12), (BLUE, 0.09), (GREEN, 0.10))
            )
        ])
        image_group = Group(photo_frame, original_photo, blur_cover, blur_pixels)

        scan_line = Line(
            photo_frame.get_left() + RIGHT * 0.12,
            photo_frame.get_right() + LEFT * 0.12,
            color=CYAN, stroke_width=4,
        ).move_to(photo_frame.get_top() + DOWN * 0.16)
        self.play(FadeIn(image_group), run_time=0.7)
        self.play(
            FadeIn(scan_line), scan_line.animate.move_to(photo_frame.get_bottom() + UP * 0.16),
            run_time=1.55, rate_func=linear,
        )
        self.play(
            Transform(original_photo, blurred_photo), FadeIn(blur_cover), FadeIn(blur_pixels),
            run_time=1.05,
        )
        self.wait(0.70)

        arrow = Arrow(LEFT * 0.65, RIGHT * 0.65, color=GREEN, stroke_width=5, buff=0)
        arrow.next_to(image_group, RIGHT, buff=0.38)

        ai_panel = RoundedRectangle(
            width=3.35, height=3.30, corner_radius=0.14,
            fill_color=PANEL, fill_opacity=1, stroke_color=BLUE, stroke_width=2,
        )
        ai_nodes = VGroup(*[
            Dot(point=point, radius=0.09, color=BLUE, fill_opacity=0.20)
            for point in (
                LEFT * 0.72 + UP * 0.68, LEFT * 0.72, LEFT * 0.72 + DOWN * 0.68,
                ORIGIN + UP * 0.34, ORIGIN + DOWN * 0.34,
                RIGHT * 0.72 + UP * 0.68, RIGHT * 0.72, RIGHT * 0.72 + DOWN * 0.68,
            )
        ]).move_to(ai_panel.get_center())
        ai_connections = VGroup(*[
            Line(first.get_center(), second.get_center(), color=BLUE, stroke_width=2, stroke_opacity=0.08)
            for first in ai_nodes[:3]
            for second in ai_nodes[3:5]
        ] + [
            Line(first.get_center(), second.get_center(), color=BLUE, stroke_width=2, stroke_opacity=0.08)
            for first in ai_nodes[3:5]
            for second in ai_nodes[5:]
        ])
        activation_rings = VGroup(*[
            Circle(radius=0.16, stroke_color=CYAN, stroke_width=2, fill_opacity=0).move_to(node)
            for node in ai_nodes
        ]).set_opacity(0)
        navigation_pulse = Dot(radius=0.07, color=CYAN).move_to(ai_nodes[1]).set_opacity(0)
        ai_group = VGroup(ai_panel, ai_connections, ai_nodes, activation_rings, navigation_pulse)

        self.play(
            FadeOut(scan_line),
            image_group.animate.scale(0.82).to_edge(LEFT, buff=0.55).shift(DOWN * 0.18),
            FadeIn(arrow), FadeIn(ai_group),
            run_time=1.45,
        )
        columns = (ai_nodes[:3], ai_nodes[3:5], ai_nodes[5:])
        ring_columns = (activation_rings[:3], activation_rings[3:5], activation_rings[5:])
        for column_index, (column, rings) in enumerate(zip(columns, ring_columns)):
            self.play(
                *[node.animate.set_opacity(1).set_color(CYAN) for node in column],
                *[ring.animate.set_opacity(1) for ring in rings],
                navigation_pulse.animate.set_opacity(1),
                run_time=0.52,
            )
            self.play(
                *[ring.animate.set_opacity(0) for ring in rings],
                run_time=0.35,
            )
            if column_index < len(columns) - 1:
                self.play(
                    navigation_pulse.animate.move_to(columns[column_index + 1][1].get_center()),
                    run_time=0.90,
                    rate_func=smooth,
                )
        self.play(
            *[connection.animate.set_opacity(0.72) for connection in ai_connections],
            run_time=1.15,
            rate_func=smooth,
        )
        self.wait(3.0)


class ProgramArchitectureScene(Scene):
    def construct(self):
        self.camera.background_color = BLACK

        # ============================================================
        # COLORS
        # ============================================================

        CYAN   = "#43E6D0"
        BLUE   = "#60A5FA"
        GREEN  = "#55D98B"
        YELLOW = "#F6C85F"

        PANEL   = "#081018"
        PANEL_2 = "#101923"
        LINE    = "#33475B"

        # ============================================================
        # ICONS
        # ============================================================

        def image_icon(color=BLUE):
            frame = RoundedRectangle(
                width=0.62,
                height=0.48,
                corner_radius=0.06,
                stroke_color=color,
                stroke_width=2.5,
            )

            sun = Dot(
                radius=0.045,
                color=color,
            ).move_to(
                frame.get_center()
                + LEFT * 0.16
                + UP * 0.11
            )

            mountains = VMobject(
                stroke_color=color,
                stroke_width=2.3,
            )

            mountains.set_points_as_corners([
                frame.get_bottom()
                + LEFT * 0.24
                + UP * 0.08,

                frame.get_center()
                + LEFT * 0.03
                + DOWN * 0.02,

                frame.get_center()
                + RIGHT * 0.10
                + UP * 0.07,

                frame.get_bottom()
                + RIGHT * 0.24
                + UP * 0.08,
            ])

            return VGroup(
                frame,
                sun,
                mountains,
            )

        def audio_icon(color=GREEN):
            bars = VGroup()

            for h in (
                0.18,
                0.38,
                0.62,
                0.34,
                0.50,
            ):
                bars.add(
                    Line(
                        DOWN * h / 2,
                        UP * h / 2,
                        color=color,
                        stroke_width=4,
                    )
                )

            bars.arrange(
                RIGHT,
                buff=0.06,
            )

            return bars

        def future_icon(color=YELLOW):
            outer = Circle(
                radius=0.28,
                stroke_color=color,
                stroke_width=2.5,
            )

            plus = VGroup(
                Line(
                    LEFT * 0.11,
                    RIGHT * 0.11,
                    color=color,
                    stroke_width=3,
                ),
                Line(
                    DOWN * 0.11,
                    UP * 0.11,
                    color=color,
                    stroke_width=3,
                ),
            )

            return VGroup(
                outer,
                plus,
            ).set_opacity(0.28)

        # ============================================================
        # USER INTERFACE
        # ============================================================

        def feed_ui():
            phone = RoundedRectangle(
                width=2.35,
                height=4.65,
                corner_radius=0.28,
                stroke_color=CYAN,
                stroke_width=3.2,
                fill_color=PANEL,
                fill_opacity=1,
            )

            screen = RoundedRectangle(
                width=1.98,
                height=4.05,
                corner_radius=0.16,
                stroke_color=LINE,
                stroke_width=1.4,
                fill_color=PANEL_2,
                fill_opacity=1,
            ).move_to(phone)

            header = Line(
                LEFT * 0.65,
                RIGHT * 0.65,
                color=CYAN,
                stroke_width=5,
            ).move_to(
                screen.get_top()
                + DOWN * 0.35
            )

            cards = VGroup()

            for _ in range(4):
                card = RoundedRectangle(
                    width=1.55,
                    height=0.50,
                    corner_radius=0.08,
                    stroke_color=LINE,
                    stroke_width=1.5,
                    fill_color=PANEL,
                    fill_opacity=1,
                )

                avatar = Circle(
                    radius=0.075,
                    stroke_color=CYAN,
                    stroke_width=1.8,
                ).move_to(
                    card.get_left()
                    + RIGHT * 0.18
                )

                line_1 = Line(
                    LEFT * 0.30,
                    RIGHT * 0.30,
                    color=BLUE,
                    stroke_width=2.6,
                ).move_to(
                    card.get_center()
                    + RIGHT * 0.20
                    + UP * 0.07
                )

                line_2 = Line(
                    LEFT * 0.21,
                    RIGHT * 0.21,
                    color=LINE,
                    stroke_width=2,
                ).move_to(
                    card.get_center()
                    + RIGHT * 0.12
                    + DOWN * 0.10
                )

                cards.add(
                    VGroup(
                        card,
                        avatar,
                        line_1,
                        line_2,
                    )
                )

            cards.arrange(
                DOWN,
                buff=0.14,
            )

            cards.move_to(
                screen.get_center()
                + DOWN * 0.10
            )

            return VGroup(
                phone,
                screen,
                header,
                cards,
            )

        # ============================================================
        # INITIAL UI
        # ============================================================

        ui = feed_ui()

        ui.move_to(
            LEFT * 4.75
        )

        # ------------------------------------------------------------
        # Build UI progressively
        # ------------------------------------------------------------

        self.play(
            Create(ui[0]),
            run_time=0.55,
            rate_func=rate_functions.ease_out_cubic,
        )

        self.play(
            FadeIn(
                ui[1],
                scale=0.96,
            ),
            GrowFromCenter(ui[2]),
            run_time=0.38,
        )

        self.play(
            LaggedStart(
                *[
                    FadeIn(
                        card,
                        shift=UP * 0.10,
                    )
                    for card in ui[3]
                ],
                lag_ratio=0.12,
            ),
            run_time=0.75,
        )

        # Scan through UI
        ui_scan = Line(
            ui.get_left() + UP * 1.65,
            ui.get_right() + UP * 1.65,
            color=CYAN,
            stroke_width=4,
            stroke_opacity=0.75,
        )

        self.add(ui_scan)

        self.play(
            ui_scan.animate.shift(
                DOWN * 3.30
            ),
            run_time=0.48,
            rate_func=rate_functions.ease_in_out_sine,
        )

        self.remove(ui_scan)

        self.wait(0.12)

        # ============================================================
        # ARCHITECTURE SEPARATOR
        # ============================================================

        separator = DashedLine(
            UP * 2.35,
            DOWN * 2.35,
            dash_length=0.12,
            color=LINE,
            stroke_width=3,
        ).move_to(
            LEFT * 2.88
        )

        self.play(
            Create(separator),
            run_time=0.42,
            rate_func=rate_functions.ease_out_expo,
        )

        # ============================================================
        # AI ENGINE
        # ============================================================

        engine_panel = RoundedRectangle(
            width=3.55,
            height=4.20,
            corner_radius=0.20,
            stroke_color=BLUE,
            stroke_width=3,
            fill_color=PANEL,
            fill_opacity=1,
        ).move_to(
            LEFT * 0.70
        )

        # ============================================================
        # AI CORE
        # ============================================================

        core_outer = Circle(
            radius=0.56,
            stroke_color=BLUE,
            stroke_width=3.5,
            fill_color=BLUE,
            fill_opacity=0.035,
        ).move_to(
            engine_panel.get_center()
        )

        core_middle = Circle(
            radius=0.34,
            stroke_color=CYAN,
            stroke_width=3,
            fill_color=CYAN,
            fill_opacity=0.075,
        ).move_to(
            core_outer
        )

        core_dot = Dot(
            radius=0.10,
            color=CYAN,
        ).move_to(
            core_outer
        )

        # ============================================================
        # INTERNAL NETWORK
        # ============================================================

        network_nodes = VGroup()

        for offset in [
            LEFT * 0.95 + UP * 0.55,
            LEFT * 0.95 + DOWN * 0.55,
            RIGHT * 0.95 + UP * 0.55,
            RIGHT * 0.95 + DOWN * 0.55,
            UP * 1.12,
            DOWN * 1.12,
        ]:
            node = Dot(
                radius=0.045,
                color=BLUE,
                fill_opacity=0.45,
            ).move_to(
                core_outer.get_center()
                + offset
            )

            network_nodes.add(node)

        network_lines = VGroup()

        for node in network_nodes:
            network_lines.add(
                Line(
                    node.get_center(),
                    core_outer.get_center(),
                    color=BLUE,
                    stroke_width=1.6,
                    stroke_opacity=0.16,
                )
            )

        # ============================================================
        # IMAGE / AUDIO / FUTURE
        # ============================================================

        image = image_icon(BLUE)

        image.move_to(
            engine_panel.get_center()
            + LEFT * 1.15
            + UP * 1.22
        )

        audio = audio_icon(GREEN)

        audio.move_to(
            engine_panel.get_center()
            + LEFT * 1.15
            + DOWN * 1.22
        )

        future = future_icon(YELLOW)

        future.move_to(
            engine_panel.get_center()
            + RIGHT * 1.18
        )

        image_line = Line(
            image.get_right(),
            core_outer.get_left(),
            color=BLUE,
            stroke_width=3.5,
            stroke_opacity=0.16,
        )

        audio_line = Line(
            audio.get_right(),
            core_outer.get_left(),
            color=GREEN,
            stroke_width=3.5,
            stroke_opacity=0.16,
        )

        future_line = DashedLine(
            core_outer.get_right(),
            future.get_left(),
            dash_length=0.07,
            color=YELLOW,
            stroke_width=2.7,
            stroke_opacity=0.14,
        )

        engine = VGroup(
            engine_panel,
            network_lines,
            network_nodes,
            image_line,
            audio_line,
            future_line,
            image,
            audio,
            future,
            core_outer,
            core_middle,
            core_dot,
        )

        # ============================================================
        # ENGINE BUILD ANIMATION
        # ============================================================

        self.play(
            Create(engine_panel),
            run_time=0.55,
            rate_func=rate_functions.ease_out_cubic,
        )

        # Network appears
        self.play(
            LaggedStart(
                *[
                    Create(line)
                    for line in network_lines
                ],
                lag_ratio=0.06,
            ),
            run_time=0.48,
        )

        self.play(
            LaggedStart(
                *[
                    FadeIn(
                        node,
                        scale=0.15,
                    )
                    for node in network_nodes
                ],
                lag_ratio=0.07,
            ),
            run_time=0.40,
        )

        # Core builds outward -> inward
        self.play(
            GrowFromCenter(core_outer),
            run_time=0.30,
            rate_func=rate_functions.ease_out_expo,
        )

        self.play(
            GrowFromCenter(core_middle),
            run_time=0.22,
        )

        self.play(
            FadeIn(
                core_dot,
                scale=0.1,
            ),
            run_time=0.16,
        )

        # Image & audio capabilities
        self.play(
            FadeIn(
                image,
                shift=RIGHT * 0.15,
            ),
            FadeIn(
                audio,
                shift=RIGHT * 0.15,
            ),
            run_time=0.38,
        )

        self.play(
            Create(image_line),
            Create(audio_line),
            run_time=0.32,
        )

        # ------------------------------------------------------------
        # Startup pulse
        # ------------------------------------------------------------

        startup_ring = Circle(
            radius=0.16,
            stroke_color=CYAN,
            stroke_width=4,
        ).move_to(
            core_dot
        )

        self.add(startup_ring)

        self.play(
            startup_ring.animate
            .scale(4.2)
            .set_opacity(0),

            core_dot.animate.scale(1.35),

            run_time=0.38,
            rate_func=rate_functions.ease_out_expo,
        )

        self.play(
            core_dot.animate.scale(
                1 / 1.35
            ),
            run_time=0.15,
        )

        self.remove(startup_ring)

        # ============================================================
        # UI -> ENGINE CONNECTION
        # ============================================================

        bridge = Arrow(
            ui.get_right(),
            engine_panel.get_left(),
            buff=0.18,
            color=GREEN,
            stroke_width=5,
            max_tip_length_to_length_ratio=0.13,
        )

        self.play(
            GrowArrow(bridge),
            run_time=0.42,
            rate_func=rate_functions.ease_out_expo,
        )

        # Light sweeps over bridge
        self.play(
            ShowPassingFlash(
                bridge.copy().set_stroke(
                    CYAN,
                    width=10,
                    opacity=1,
                ),
                time_width=0.25,
            ),
            run_time=0.42,
        )

        # Physical data pulse
        input_pulse = Dot(
            radius=0.11,
            color=GREEN,
        ).move_to(
            bridge.get_start()
        )

        input_path = Line(
            bridge.get_start(),
            bridge.get_end(),
        )

        self.add(input_pulse)

        self.play(
            MoveAlongPath(
                input_pulse,
                input_path,
            ),
            run_time=0.40,
            rate_func=rate_functions.ease_in_cubic,
        )

        self.remove(input_pulse)

        impact_ring = Circle(
            radius=0.12,
            stroke_color=GREEN,
            stroke_width=4,
        ).move_to(
            bridge.get_end()
        )

        self.add(impact_ring)

        self.play(
            impact_ring.animate
            .scale(3)
            .set_opacity(0),

            engine_panel.animate.set_stroke(
                CYAN,
                width=5,
            ),

            run_time=0.24,
            rate_func=rate_functions.ease_out_expo,
        )

        self.play(
            engine_panel.animate.set_stroke(
                BLUE,
                width=3,
            ),
            run_time=0.20,
        )

        self.remove(impact_ring)

        # ============================================================
        # IMAGE DATA
        # ============================================================

        image.set_opacity(0.30)

        image_packet = image_icon(BLUE).scale(0.72)

        image_packet.move_to(
            ui.get_right()
        )

        image_path = ArcBetweenPoints(
            ui.get_right(),
            image.get_center(),
            angle=-0.20,
        )

        self.add(image_packet)

        self.play(
            MoveAlongPath(
                image_packet,
                image_path,
            ),
            run_time=0.62,
            rate_func=rate_functions.ease_in_out_cubic,
        )

        self.play(
            FadeOut(
                image_packet,
                scale=0.30,
            ),

            image.animate.set_opacity(1),

            image_line.animate.set_stroke(
                opacity=0.85,
                width=4,
            ),

            run_time=0.18,
        )

        # ------------------------------------------------------------
        # Image -> Core
        # ------------------------------------------------------------

        image_energy = Dot(
            radius=0.07,
            color=BLUE,
        ).move_to(
            image.get_right()
        )

        image_to_core = Line(
            image.get_right(),
            core_outer.get_center(),
        )

        self.add(image_energy)

        self.play(
            MoveAlongPath(
                image_energy,
                image_to_core,
            ),

            ShowPassingFlash(
                image_line.copy().set_stroke(
                    BLUE,
                    width=8,
                    opacity=1,
                ),
                time_width=0.25,
            ),

            run_time=0.34,
            rate_func=rate_functions.ease_in_cubic,
        )

        self.remove(image_energy)

        image_hit = Circle(
            radius=0.12,
            stroke_color=BLUE,
            stroke_width=4,
        ).move_to(
            core_dot
        )

        self.add(image_hit)

        self.play(
            image_hit.animate
            .scale(3.2)
            .set_opacity(0),

            core_outer.animate.set_stroke(
                CYAN,
                width=5,
            ),

            core_dot.animate.scale(1.35),

            run_time=0.23,
            rate_func=rate_functions.ease_out_expo,
        )

        self.play(
            core_outer.animate.set_stroke(
                BLUE,
                width=3.5,
            ),

            core_dot.animate.scale(
                1 / 1.35
            ),

            run_time=0.18,
        )

        self.remove(image_hit)

        # ============================================================
        # AUDIO DATA
        # ============================================================

        audio.set_opacity(0.30)

        audio_packet = audio_icon(GREEN).scale(0.72)

        audio_packet.move_to(
            ui.get_right()
        )

        audio_path = ArcBetweenPoints(
            ui.get_right(),
            audio.get_center(),
            angle=0.20,
        )

        self.add(audio_packet)

        self.play(
            MoveAlongPath(
                audio_packet,
                audio_path,
            ),
            run_time=0.62,
            rate_func=rate_functions.ease_in_out_cubic,
        )

        self.play(
            FadeOut(
                audio_packet,
                scale=0.30,
            ),

            audio.animate.set_opacity(1),

            audio_line.animate.set_stroke(
                opacity=0.85,
                width=4,
            ),

            run_time=0.18,
        )

        # ------------------------------------------------------------
        # Audio -> Core
        # ------------------------------------------------------------

        audio_energy = Dot(
            radius=0.07,
            color=GREEN,
        ).move_to(
            audio.get_right()
        )

        audio_to_core = Line(
            audio.get_right(),
            core_outer.get_center(),
        )

        self.add(audio_energy)

        self.play(
            MoveAlongPath(
                audio_energy,
                audio_to_core,
            ),

            ShowPassingFlash(
                audio_line.copy().set_stroke(
                    GREEN,
                    width=8,
                    opacity=1,
                ),
                time_width=0.25,
            ),

            run_time=0.34,
            rate_func=rate_functions.ease_in_cubic,
        )

        self.remove(audio_energy)

        audio_hit = Circle(
            radius=0.12,
            stroke_color=GREEN,
            stroke_width=4,
        ).move_to(
            core_dot
        )

        self.add(audio_hit)

        self.play(
            audio_hit.animate
            .scale(3.2)
            .set_opacity(0),

            core_middle.animate.set_stroke(
                GREEN,
                width=5,
            ),

            core_dot.animate.scale(1.35),

            run_time=0.23,
            rate_func=rate_functions.ease_out_expo,
        )

        self.play(
            core_middle.animate.set_stroke(
                CYAN,
                width=3,
            ),

            core_dot.animate.scale(
                1 / 1.35
            ),

            run_time=0.18,
        )

        self.remove(audio_hit)

        # ============================================================
        # AI PROCESSING
        # ============================================================

        processing_ring = Circle(
            radius=core_outer.width / 2,
            stroke_color=CYAN,
            stroke_width=5,
        ).move_to(
            core_outer
        )

        self.add(processing_ring)

        self.play(
            processing_ring.animate
            .scale(1.75)
            .set_opacity(0),

            core_dot.animate
            .set_color(WHITE)
            .scale(1.7),

            run_time=0.30,
            rate_func=rate_functions.ease_out_expo,
        )

        self.remove(processing_ring)

        # ------------------------------------------------------------
        # Processing spreads through the network
        # ------------------------------------------------------------

        self.play(
            LaggedStart(
                *[
                    ShowPassingFlash(
                        line.copy().set_stroke(
                            CYAN,
                            width=5,
                            opacity=1,
                        ),
                        time_width=0.35,
                    )
                    for line in network_lines
                ],
                lag_ratio=0.07,
            ),

            LaggedStart(
                *[
                    node.animate
                    .set_color(CYAN)
                    .set_opacity(1)
                    for node in network_nodes
                ],
                lag_ratio=0.07,
            ),

            run_time=0.65,
        )

        # Network relaxes
        self.play(
            *[
                node.animate
                .set_color(BLUE)
                .set_opacity(0.45)
                for node in network_nodes
            ],

            core_dot.animate
            .set_color(CYAN)
            .scale(1 / 1.7),

            run_time=0.30,
        )

        # ============================================================
        # FUTURE EXPANSION
        # ============================================================

        self.play(
            Create(future_line),
            run_time=0.36,
            rate_func=rate_functions.ease_out_cubic,
        )

        self.play(
            FadeIn(future),
            future.animate.set_opacity(0.72),
            run_time=0.28,
        )

        future_wave = Circle(
            radius=0.15,
            stroke_color=YELLOW,
            stroke_width=3,
        ).move_to(
            future
        )

        self.add(future_wave)

        self.play(
            future_wave.animate
            .scale(2.8)
            .set_opacity(0),

            run_time=0.35,
            rate_func=rate_functions.ease_out_expo,
        )

        self.remove(future_wave)

        self.wait(0.18)

        # ============================================================
        # SHIFT ARCHITECTURE LEFT
        # ============================================================

        self.play(
            ui.animate.shift(
                LEFT * 0.35
            ),

            separator.animate.shift(
                LEFT * 0.22
            ),

            engine.animate.shift(
                LEFT * 0.62
            ),

            bridge.animate.shift(
                LEFT * 0.48
            ),

            run_time=0.80,
            rate_func=rate_functions.ease_in_out_sine,
        )

        # ============================================================
        # PLATFORM CARDS
        # ============================================================

        def platform_box(
            logo,
            color,
            logo_height=None,
            logo_width=None,
        ):
            box = RoundedRectangle(
                width=1.42,
                height=1.42,
                corner_radius=0.18,
                stroke_color=color,
                stroke_width=2.8,
                fill_color=PANEL,
                fill_opacity=1,
            )

            if logo_height is not None:
                logo.set_height(
                    logo_height
                )

            if logo_width is not None:
                logo.set_width(
                    logo_width
                )

            logo.set_fill(color)
            logo.set_stroke(color)
            logo.move_to(box)

            return VGroup(
                box,
                logo,
            )

        # ------------------------------------------------------------
        # iOS
        # ------------------------------------------------------------

        ios_logo = SVGMobject(
            ROOT / "assets" / "platforms" / "ios.svg"
        )

        ios_group = platform_box(
            ios_logo,
            CYAN,
            logo_height=0.68,
        )

        # ------------------------------------------------------------
        # Windows
        # ------------------------------------------------------------

        windows_logo = SVGMobject(
            ROOT / "assets" / "platforms" / "windows.svg"
        )

        windows_group = platform_box(
            windows_logo,
            BLUE,
            logo_width=0.68,
        )

        # ------------------------------------------------------------
        # Linux
        # ------------------------------------------------------------

        linux_logo = SVGMobject(
            ROOT / "assets" / "platforms" / "linux.svg"
        )

        linux_group = platform_box(
            linux_logo,
            YELLOW,
            logo_height=0.78,
        )

        platforms = VGroup(
            ios_group,
            windows_group,
            linux_group,
        )

        platforms.arrange(
            DOWN,
            buff=0.25,
        )

        platforms.move_to(
            RIGHT * 5.0
        )

        # ============================================================
        # OUTPUT NODE
        # ============================================================

        output_point = Dot(
            radius=0.12,
            color=GREEN,
        ).move_to(
            engine_panel.get_right()
            + LEFT * 0.62
        )

        self.play(
            FadeIn(
                output_point,
                scale=0.1,
            ),
            run_time=0.16,
        )

        output_wave = Circle(
            radius=0.12,
            stroke_color=GREEN,
            stroke_width=4,
        ).move_to(
            output_point
        )

        self.add(output_wave)

        self.play(
            output_wave.animate
            .scale(4)
            .set_opacity(0),

            run_time=0.32,
            rate_func=rate_functions.ease_out_expo,
        )

        self.remove(output_wave)

        # ============================================================
        # ENGINE -> PLATFORMS
        # ============================================================

        arrows = VGroup()

        for platform in platforms:
            arrow = Arrow(
                output_point.get_center(),
                platform.get_left(),
                buff=0.14,
                color=GREEN,
                stroke_width=5,
                max_tip_length_to_length_ratio=0.10,
            )

            arrows.add(arrow)

        # Platforms invisible initially
        for platform in platforms:
            platform.set_opacity(0)

        self.add(*platforms)

        # ------------------------------------------------------------
        # Branches appear one by one
        # ------------------------------------------------------------

        for arrow, platform in zip(
            arrows,
            platforms,
        ):
            self.play(
                GrowArrow(arrow),

                platform.animate.set_opacity(1),

                run_time=0.34,
                rate_func=rate_functions.ease_out_cubic,
            )

        # ============================================================
        # POWER DISTRIBUTION
        # ============================================================

        power_pulses = VGroup()
        power_paths = []

        for platform in platforms:
            pulse = Dot(
                radius=0.095,
                color=GREEN,
            ).move_to(
                output_point
            )

            path = Line(
                output_point.get_center(),
                platform.get_left(),
            )

            power_pulses.add(pulse)
            power_paths.append(path)

        self.add(
            *power_pulses
        )

        # ------------------------------------------------------------
        # All three receive the same engine output simultaneously
        # ------------------------------------------------------------

        self.play(
            *[
                MoveAlongPath(
                    pulse,
                    path,
                )
                for pulse, path in zip(
                    power_pulses,
                    power_paths,
                )
            ],

            *[
                ShowPassingFlash(
                    arrow.copy().set_stroke(
                        GREEN,
                        width=9,
                        opacity=1,
                    ),
                    time_width=0.25,
                )
                for arrow in arrows
            ],

            run_time=0.52,
            rate_func=rate_functions.ease_in_out_cubic,
        )

        self.remove(
            *power_pulses
        )

        # Platform reaction
        self.play(
            ios_group[0].animate.set_stroke(
                CYAN,
                width=5,
            ),

            windows_group[0].animate.set_stroke(
                BLUE,
                width=5,
            ),

            linux_group[0].animate.set_stroke(
                YELLOW,
                width=5,
            ),

            run_time=0.15,
        )

        self.play(
            ios_group[0].animate.set_stroke(
                CYAN,
                width=2.8,
            ),

            windows_group[0].animate.set_stroke(
                BLUE,
                width=2.8,
            ),

            linux_group[0].animate.set_stroke(
                YELLOW,
                width=2.8,
            ),

            run_time=0.30,
        )

        # ============================================================
        # FINAL FLOW
        # UI -> CORE -> PLATFORMS
        # ============================================================

        # ------------------------------------------------------------
        # UI -> engine pulse
        # ------------------------------------------------------------

        self.play(
            ShowPassingFlash(
                bridge.copy().set_stroke(
                    CYAN,
                    width=9,
                    opacity=1,
                ),
                time_width=0.25,
            ),
            run_time=0.35,
        )

        # ------------------------------------------------------------
        # Core reacts
        # ------------------------------------------------------------

        core_wave = Circle(
            radius=0.18,
            stroke_color=GREEN,
            stroke_width=5,
        ).move_to(
            core_dot
        )

        self.add(core_wave)

        self.play(
            core_wave.animate
            .scale(4.5)
            .set_opacity(0),

            core_dot.animate
            .set_color(WHITE)
            .scale(1.6),

            core_outer.animate.set_stroke(
                GREEN,
                width=5,
            ),

            run_time=0.30,
            rate_func=rate_functions.ease_out_expo,
        )

        self.remove(core_wave)

        # ------------------------------------------------------------
        # Core -> platforms
        # ------------------------------------------------------------

        self.play(
            *[
                ShowPassingFlash(
                    arrow.copy().set_stroke(
                        GREEN,
                        width=9,
                        opacity=1,
                    ),
                    time_width=0.25,
                )
                for arrow in arrows
            ],

            core_dot.animate
            .set_color(CYAN)
            .scale(1 / 1.6),

            core_outer.animate.set_stroke(
                BLUE,
                width=3.5,
            ),

            run_time=0.52,
        )

        # ------------------------------------------------------------
        # Final subtle platform activation
        # ------------------------------------------------------------

        self.play(
            ios_group[1].animate.set_opacity(0.55),
            windows_group[1].animate.set_opacity(0.55),
            linux_group[1].animate.set_opacity(0.55),
            run_time=0.20,
        )

        self.play(
            ios_group[1].animate.set_opacity(1),
            windows_group[1].animate.set_opacity(1),
            linux_group[1].animate.set_opacity(1),
            run_time=0.25,
        )

        self.wait(2.5)

class LegacyNativeVsCrossPlatformScene(Scene):
    def construct(self):
        self.camera.background_color = BLACK

        CYAN = "#43E6D0"
        BLUE = "#60A5FA"
        GREEN = "#55D98B"
        YELLOW = "#F6C85F"
        RED = "#FF6B6B"
        PURPLE = "#A78BFA"

        PANEL = "#081018"
        PANEL_2 = "#101923"
        LINE = "#33475B"
        WHITE_SOFT = "#E5EEF8"

        # ============================================================
        # HELPERS
        # ============================================================

        def phone(color):
            outer = RoundedRectangle(
                width=1.55,
                height=2.85,
                corner_radius=0.22,
                stroke_color=color,
                stroke_width=3,
                fill_color=PANEL,
                fill_opacity=1,
            )

            screen = RoundedRectangle(
                width=1.25,
                height=2.35,
                corner_radius=0.12,
                stroke_color=LINE,
                stroke_width=1.3,
                fill_color=PANEL_2,
                fill_opacity=1,
            ).move_to(outer)

            notch = RoundedRectangle(
                width=0.42,
                height=0.08,
                corner_radius=0.04,
                stroke_width=0,
                fill_color=color,
                fill_opacity=0.7,
            ).move_to(
                outer.get_top() + DOWN * 0.18
            )

            return VGroup(
                outer,
                screen,
                notch,
            )

        def code_node(color):
            box = RoundedRectangle(
                width=1.55,
                height=1.35,
                corner_radius=0.18,
                stroke_color=color,
                stroke_width=3,
                fill_color=PANEL,
                fill_opacity=1,
            )

            lines = VGroup(
                Line(
                    LEFT * 0.45,
                    RIGHT * 0.33,
                    color=color,
                    stroke_width=3,
                ),
                Line(
                    LEFT * 0.34,
                    RIGHT * 0.44,
                    color=color,
                    stroke_width=3,
                ),
                Line(
                    LEFT * 0.45,
                    RIGHT * 0.18,
                    color=color,
                    stroke_width=3,
                ),
            ).arrange(
                DOWN,
                buff=0.16,
                aligned_edge=LEFT,
            ).move_to(box)

            return VGroup(
                box,
                lines,
            )

        def permission_icon(color):
            outer = Circle(
                radius=0.34,
                stroke_color=color,
                stroke_width=3,
                fill_color=PANEL,
                fill_opacity=1,
            )

            key_head = Circle(
                radius=0.09,
                stroke_color=color,
                stroke_width=3,
            ).move_to(
                outer.get_center() + LEFT * 0.07 + UP * 0.04
            )

            key_body = Line(
                key_head.get_right(),
                key_head.get_right() + RIGHT * 0.20,
                color=color,
                stroke_width=3,
            )

            tooth = Line(
                key_body.get_end(),
                key_body.get_end() + DOWN * 0.09,
                color=color,
                stroke_width=3,
            )

            return VGroup(
                outer,
                key_head,
                key_body,
                tooth,
            )

        def screen_capture_icon(color):
            frame = RoundedRectangle(
                width=1.25,
                height=0.82,
                corner_radius=0.10,
                stroke_color=color,
                stroke_width=3,
                fill_color=PANEL,
                fill_opacity=1,
            )

            corners = VGroup(
                Line(
                    frame.get_corner(UL),
                    frame.get_corner(UL) + RIGHT * 0.16,
                    color=color,
                    stroke_width=3,
                ),
                Line(
                    frame.get_corner(UL),
                    frame.get_corner(UL) + DOWN * 0.16,
                    color=color,
                    stroke_width=3,
                ),
                Line(
                    frame.get_corner(DR),
                    frame.get_corner(DR) + LEFT * 0.16,
                    color=color,
                    stroke_width=3,
                ),
                Line(
                    frame.get_corner(DR),
                    frame.get_corner(DR) + UP * 0.16,
                    color=color,
                    stroke_width=3,
                ),
            )

            eye = VGroup(
                ArcBetweenPoints(
                    LEFT * 0.23,
                    RIGHT * 0.23,
                    angle=-PI / 2,
                    color=color,
                    stroke_width=2.5,
                ),
                ArcBetweenPoints(
                    LEFT * 0.23,
                    RIGHT * 0.23,
                    angle=PI / 2,
                    color=color,
                    stroke_width=2.5,
                ),
                Dot(
                    radius=0.045,
                    color=color,
                ),
            ).move_to(frame)

            return VGroup(
                frame,
                corners,
                eye,
            )

        def blur_grid(width=1.0, height=0.72):
            cells = VGroup()

            cols = 5
            rows = 4

            cell_w = width / cols
            cell_h = height / rows

            colors = [
                BLUE,
                PURPLE,
                CYAN,
                GREEN,
                YELLOW,
                RED,
            ]

            for row in range(rows):
                for col in range(cols):
                    cell = Rectangle(
                        width=cell_w,
                        height=cell_h,
                        stroke_width=0,
                        fill_color=colors[(row + col) % len(colors)],
                        fill_opacity=0.45,
                    )

                    cell.move_to(
                        LEFT * width / 2
                        + RIGHT * (cell_w / 2 + col * cell_w)
                        + UP * height / 2
                        + DOWN * (cell_h / 2 + row * cell_h)
                    )

                    cells.add(cell)

            return cells

        # ============================================================
        # START
        # One language / programming decision
        # ============================================================

        center_core = Circle(
            radius=0.46,
            stroke_color=CYAN,
            stroke_width=4,
            fill_color=CYAN,
            fill_opacity=0.08,
        )

        inner_core = Circle(
            radius=0.18,
            stroke_color=CYAN,
            stroke_width=3,
            fill_color=CYAN,
            fill_opacity=0.35,
        )

        core = VGroup(
            center_core,
            inner_core,
        )

        self.play(
            GrowFromCenter(center_core),
            FadeIn(
                inner_core,
                scale=0.2,
            ),
            run_time=0.6,
        )

        pulse = Circle(
            radius=0.18,
            stroke_color=CYAN,
            stroke_width=4,
        )

        self.add(pulse)

        self.play(
            pulse.animate
            .scale(4)
            .set_opacity(0),
            run_time=0.45,
            rate_func=rate_functions.ease_out_expo,
        )

        self.remove(pulse)

        # ============================================================
        # SPLIT INTO TWO OPTIONS
        # ============================================================

        native_core = code_node(BLUE)
        native_core.move_to(
            LEFT * 3.6 + UP * 0.9
        )

        cross_core = code_node(PURPLE)
        cross_core.move_to(
            RIGHT * 3.6 + UP * 0.9
        )

        native_path = Arrow(
            core.get_center(),
            native_core.get_center(),
            buff=0.55,
            color=BLUE,
            stroke_width=5,
        )

        cross_path = Arrow(
            core.get_center(),
            cross_core.get_center(),
            buff=0.55,
            color=PURPLE,
            stroke_width=5,
        )

        self.play(
            GrowArrow(native_path),
            GrowArrow(cross_path),
            run_time=0.7,
        )

        self.play(
            FadeIn(
                native_core,
                shift=UP * 0.15,
            ),
            FadeIn(
                cross_core,
                shift=UP * 0.15,
            ),
            run_time=0.5,
        )

        self.play(
            FadeOut(core),
            FadeOut(native_path),
            FadeOut(cross_path),
            run_time=0.35,
        )

        # ============================================================
        # NATIVE
        # Two independent implementations
        # ============================================================

        native_android_code = code_node(GREEN).scale(0.72)
        native_ios_code = code_node(CYAN).scale(0.72)

        native_android_code.move_to(
            LEFT * 4.55 + DOWN * 1.25
        )

        native_ios_code.move_to(
            LEFT * 2.65 + DOWN * 1.25
        )

        native_split_a = Arrow(
            native_core.get_bottom(),
            native_android_code.get_top(),
            buff=0.12,
            color=GREEN,
            stroke_width=4,
        )

        native_split_b = Arrow(
            native_core.get_bottom(),
            native_ios_code.get_top(),
            buff=0.12,
            color=CYAN,
            stroke_width=4,
        )

        self.play(
            GrowArrow(native_split_a),
            GrowArrow(native_split_b),
            run_time=0.45,
        )

        self.play(
            FadeIn(native_android_code),
            FadeIn(native_ios_code),
            run_time=0.4,
        )

        # Phones
        android_phone = phone(GREEN).scale(0.78)
        ios_phone = phone(CYAN).scale(0.78)

        android_phone.move_to(
            LEFT * 4.55 + DOWN * 2.75
        )

        ios_phone.move_to(
            LEFT * 2.65 + DOWN * 2.75
        )

        native_to_android = Arrow(
            native_android_code.get_bottom(),
            android_phone.get_top(),
            buff=0.10,
            color=GREEN,
            stroke_width=3.5,
        )

        native_to_ios = Arrow(
            native_ios_code.get_bottom(),
            ios_phone.get_top(),
            buff=0.10,
            color=CYAN,
            stroke_width=3.5,
        )

        self.play(
            GrowArrow(native_to_android),
            GrowArrow(native_to_ios),
            FadeIn(android_phone),
            FadeIn(ios_phone),
            run_time=0.55,
        )

        # ============================================================
        # CROSS PLATFORM
        # One implementation shared by both
        # ============================================================

        shared_code = code_node(PURPLE).scale(0.82)
        shared_code.move_to(
            RIGHT * 3.6 + DOWN * 1.0
        )

        shared_path = Arrow(
            cross_core.get_bottom(),
            shared_code.get_top(),
            buff=0.12,
            color=PURPLE,
            stroke_width=4,
        )

        self.play(
            GrowArrow(shared_path),
            FadeIn(shared_code),
            run_time=0.45,
        )

        cross_android = phone(GREEN).scale(0.72)
        cross_ios = phone(CYAN).scale(0.72)

        cross_android.move_to(
            RIGHT * 2.65 + DOWN * 2.75
        )

        cross_ios.move_to(
            RIGHT * 4.55 + DOWN * 2.75
        )

        cross_a = Arrow(
            shared_code.get_bottom(),
            cross_android.get_top(),
            buff=0.12,
            color=PURPLE,
            stroke_width=3.5,
        )

        cross_b = Arrow(
            shared_code.get_bottom(),
            cross_ios.get_top(),
            buff=0.12,
            color=PURPLE,
            stroke_width=3.5,
        )

        self.play(
            GrowArrow(cross_a),
            GrowArrow(cross_b),
            FadeIn(cross_android),
            FadeIn(cross_ios),
            run_time=0.55,
        )

        # Highlight single shared source
        shared_pulse = RoundedRectangle(
            width=shared_code.width + 0.22,
            height=shared_code.height + 0.22,
            corner_radius=0.22,
            stroke_color=PURPLE,
            stroke_width=4,
        ).move_to(shared_code)

        self.play(
            FadeIn(shared_pulse),
            shared_pulse.animate
            .scale(1.13)
            .set_opacity(0),
            run_time=0.6,
        )

        self.remove(shared_pulse)

        self.wait(0.3)

        # ============================================================
        # BOTH ARE VALID
        # ============================================================

        native_group = VGroup(
            native_core,
            native_split_a,
            native_split_b,
            native_android_code,
            native_ios_code,
            native_to_android,
            native_to_ios,
            android_phone,
            ios_phone,
        )

        cross_group = VGroup(
            cross_core,
            shared_path,
            shared_code,
            cross_a,
            cross_b,
            cross_android,
            cross_ios,
        )

        native_border = RoundedRectangle(
            width=native_group.width + 0.45,
            height=native_group.height + 0.35,
            corner_radius=0.22,
            stroke_color=BLUE,
            stroke_width=2,
        ).move_to(native_group)

        cross_border = RoundedRectangle(
            width=cross_group.width + 0.45,
            height=cross_group.height + 0.35,
            corner_radius=0.22,
            stroke_color=PURPLE,
            stroke_width=2,
        ).move_to(cross_group)

        self.play(
            Create(native_border),
            Create(cross_border),
            run_time=0.45,
        )

        self.play(
            native_border.animate.set_opacity(0.25),
            cross_border.animate.set_opacity(0.25),
            run_time=0.35,
        )

        # ============================================================
        # NOW FOCUS ON THE ACTUAL REQUIREMENT
        # Screen capture + monitoring
        # ============================================================

        self.play(
            FadeOut(cross_group),
            FadeOut(cross_border),
            FadeOut(native_border),
            native_group.animate
            .scale(0.58)
            .to_edge(LEFT, buff=0.45)
            .shift(UP * 0.25),
            run_time=0.8,
        )

        capture = screen_capture_icon(YELLOW)
        capture.scale(1.35)
        capture.move_to(
            RIGHT * 1.1 + UP * 0.95
        )

        self.play(
            FadeIn(
                capture,
                scale=0.75,
            ),
            run_time=0.45,
        )

        scan_line = Line(
            capture.get_left() + RIGHT * 0.08,
            capture.get_right() + LEFT * 0.08,
            color=YELLOW,
            stroke_width=4,
        )

        scan_line.move_to(
            capture.get_top() + DOWN * 0.15
        )

        self.add(scan_line)

        self.play(
            scan_line.animate.move_to(
                capture.get_bottom() + UP * 0.15
            ),
            run_time=0.85,
            rate_func=linear,
        )

        self.remove(scan_line)

        # ============================================================
        # DIFFERENT PLATFORM PERMISSIONS
        # ============================================================

        android_permission = permission_icon(GREEN)
        ios_permission = permission_icon(CYAN)

        android_permission.move_to(
            RIGHT * 0.2 + DOWN * 1.0
        )

        ios_permission.move_to(
            RIGHT * 3.2 + DOWN * 1.0
        )

        capture_to_android = Arrow(
            capture.get_bottom(),
            android_permission.get_top(),
            buff=0.20,
            color=GREEN,
            stroke_width=4,
        )

        capture_to_ios = Arrow(
            capture.get_bottom(),
            ios_permission.get_top(),
            buff=0.20,
            color=CYAN,
            stroke_width=4,
        )

        self.play(
            GrowArrow(capture_to_android),
            GrowArrow(capture_to_ios),
            FadeIn(android_permission),
            FadeIn(ios_permission),
            run_time=0.6,
        )

        # Different paths beneath permissions
        android_layers = VGroup(
            Circle(
                radius=0.12,
                color=GREEN,
                fill_opacity=0.25,
            ),
            Circle(
                radius=0.12,
                color=GREEN,
                fill_opacity=0.45,
            ),
            Circle(
                radius=0.12,
                color=GREEN,
                fill_opacity=0.70,
            ),
        ).arrange(
            DOWN,
            buff=0.25,
        ).next_to(
            android_permission,
            DOWN,
            buff=0.32,
        )

        ios_layers = VGroup(
            RoundedRectangle(
                width=0.38,
                height=0.18,
                corner_radius=0.05,
                stroke_color=CYAN,
                stroke_width=2,
            ),
            RoundedRectangle(
                width=0.62,
                height=0.18,
                corner_radius=0.05,
                stroke_color=CYAN,
                stroke_width=2,
            ),
        ).arrange(
            DOWN,
            buff=0.35,
        ).next_to(
            ios_permission,
            DOWN,
            buff=0.32,
        )

        android_permission_path = DashedLine(
            android_permission.get_bottom(),
            android_layers.get_top(),
            color=GREEN,
            stroke_width=3,
            dash_length=0.09,
        )

        ios_permission_path = ArcBetweenPoints(
            ios_permission.get_bottom(),
            ios_layers.get_top(),
            angle=-0.55,
            color=CYAN,
            stroke_width=3,
        )

        self.play(
            Create(android_permission_path),
            Create(ios_permission_path),
            LaggedStart(
                *[
                    FadeIn(
                        item,
                        scale=0.3,
                    )
                    for item in android_layers
                ],
                lag_ratio=0.15,
            ),
            LaggedStart(
                *[
                    FadeIn(
                        item,
                        scale=0.3,
                    )
                    for item in ios_layers
                ],
                lag_ratio=0.18,
            ),
            run_time=0.8,
        )

        # Visual emphasis that platform handling differs
        self.play(
            ShowPassingFlash(
                android_permission_path.copy().set_stroke(
                    GREEN,
                    width=8,
                ),
                time_width=0.3,
            ),
            run_time=0.5,
        )

        self.play(
            ShowPassingFlash(
                ios_permission_path.copy().set_stroke(
                    CYAN,
                    width=8,
                ),
                time_width=0.3,
            ),
            run_time=0.5,
        )

        # ============================================================
        # SCREEN CONTENT
        # ============================================================

        self.play(
            FadeOut(native_group),
            FadeOut(android_permission),
            FadeOut(ios_permission),
            FadeOut(android_layers),
            FadeOut(ios_layers),
            FadeOut(android_permission_path),
            FadeOut(ios_permission_path),
            FadeOut(capture_to_android),
            FadeOut(capture_to_ios),
            capture.animate
            .scale(0.75)
            .to_edge(LEFT, buff=1.0),
            run_time=0.75,
        )

        monitored_phone = phone(BLUE).scale(1.45)
        monitored_phone.move_to(
            RIGHT * 1.4
        )

        self.play(
            FadeIn(
                monitored_phone,
                shift=UP * 0.12,
            ),
            run_time=0.5,
        )

        # Content inside phone screen
        content_frame = RoundedRectangle(
            width=1.35,
            height=0.95,
            corner_radius=0.08,
            stroke_color=LINE,
            stroke_width=1.5,
            fill_color="#17202A",
            fill_opacity=1,
        ).move_to(
            monitored_phone[1].get_center() + UP * 0.35
        )

        person_head = Circle(
            radius=0.16,
            fill_color=WHITE_SOFT,
            fill_opacity=0.7,
            stroke_width=0,
        ).move_to(
            content_frame.get_center() + UP * 0.15
        )

        person_body = RoundedRectangle(
            width=0.52,
            height=0.38,
            corner_radius=0.15,
            fill_color=WHITE_SOFT,
            fill_opacity=0.55,
            stroke_width=0,
        ).next_to(
            person_head,
            DOWN,
            buff=0.04,
        )

        sensitive_content = VGroup(
            content_frame,
            person_head,
            person_body,
        )

        normal_lines = VGroup(
            Line(
                LEFT * 0.45,
                RIGHT * 0.45,
                color=LINE,
                stroke_width=3,
            ),
            Line(
                LEFT * 0.35,
                RIGHT * 0.22,
                color=LINE,
                stroke_width=3,
            ),
            Line(
                LEFT * 0.42,
                RIGHT * 0.38,
                color=LINE,
                stroke_width=3,
            ),
        ).arrange(
            DOWN,
            buff=0.22,
            aligned_edge=LEFT,
        ).move_to(
            monitored_phone[1].get_center() + DOWN * 0.72
        )

        self.play(
            FadeIn(sensitive_content),
            FadeIn(normal_lines),
            run_time=0.4,
        )

        # ============================================================
        # LIVE MONITORING CONNECTION
        # ============================================================

        monitor_arrow = Arrow(
            capture.get_right(),
            monitored_phone.get_left(),
            buff=0.22,
            color=YELLOW,
            stroke_width=5,
        )

        self.play(
            GrowArrow(monitor_arrow),
            run_time=0.4,
        )

        packet = Dot(
            radius=0.10,
            color=YELLOW,
        ).move_to(
            monitor_arrow.get_start()
        )

        self.add(packet)

        self.play(
            MoveAlongPath(
                packet,
                Line(
                    monitor_arrow.get_start(),
                    monitor_arrow.get_end(),
                ),
            ),
            run_time=0.55,
            rate_func=linear,
        )

        self.remove(packet)

        # ============================================================
        # DETECTION
        # ============================================================

        detect_box = RoundedRectangle(
            width=content_frame.width + 0.10,
            height=content_frame.height + 0.10,
            corner_radius=0.10,
            stroke_color=RED,
            stroke_width=4,
        ).move_to(content_frame)

        self.play(
            Create(detect_box),
            run_time=0.3,
        )

        self.play(
            detect_box.animate
            .scale(1.06),
            run_time=0.18,
            rate_func=there_and_back,
        )

        # ============================================================
        # BLUR
        # ============================================================

        blurred = blur_grid(
            width=content_frame.width - 0.06,
            height=content_frame.height - 0.06,
        ).move_to(content_frame)

        dark_overlay = RoundedRectangle(
            width=content_frame.width,
            height=content_frame.height,
            corner_radius=0.08,
            stroke_width=0,
            fill_color=BLACK,
            fill_opacity=0.25,
        ).move_to(content_frame)

        self.play(
            FadeOut(
                VGroup(
                    person_head,
                    person_body,
                ),
                run_time=0.15,
            ),
            FadeIn(
                blurred,
                lag_ratio=0.03,
            ),
            FadeIn(dark_overlay),
            detect_box.animate.set_stroke(
                GREEN,
                width=4,
            ),
            run_time=0.65,
        )

        # ============================================================
        # NATIVE BECOMES THE PREFERRED ROUTE
        # ============================================================

        native_symbol = VGroup(
            code_node(GREEN).scale(0.75),
            phone(GREEN).scale(0.55),
            phone(CYAN).scale(0.55),
        )

        native_symbol[1].next_to(
            native_symbol[0],
            DOWN + LEFT,
            buff=0.28,
        )

        native_symbol[2].next_to(
            native_symbol[0],
            DOWN + RIGHT,
            buff=0.28,
        )

        native_symbol.move_to(
            LEFT * 4.5 + DOWN * 2.0
        )

        native_links = VGroup(
            Arrow(
                native_symbol[0].get_bottom(),
                native_symbol[1].get_top(),
                buff=0.08,
                color=GREEN,
                stroke_width=3,
            ),
            Arrow(
                native_symbol[0].get_bottom(),
                native_symbol[2].get_top(),
                buff=0.08,
                color=CYAN,
                stroke_width=3,
            ),
        )

        native_glow = Circle(
            radius=1.22,
            stroke_color=GREEN,
            stroke_width=4,
        ).move_to(native_symbol)

        native_glow.set_opacity(0)

        self.play(
            FadeIn(native_symbol),
            GrowArrow(native_links[0]),
            GrowArrow(native_links[1]),
            run_time=0.55,
        )

        self.add(native_glow)

        self.play(
            native_glow.animate
            .set_opacity(1)
            .scale(1.12),
            run_time=0.25,
        )

        self.play(
            native_glow.animate
            .scale(1.22)
            .set_opacity(0),
            native_symbol.animate.scale(1.07),
            run_time=0.45,
            rate_func=rate_functions.ease_out_expo,
        )

        self.remove(native_glow)

        self.play(
            native_symbol.animate.scale(1 / 1.07),
            ShowPassingFlash(
                monitor_arrow.copy().set_stroke(
                    GREEN,
                    width=9,
                    opacity=1,
                ),
                time_width=0.25,
            ),
            run_time=0.45,
        )

        self.wait(2.5)

BRAND = ROOT / 'assets' / 'brand'

class NativeVsCrossPlatformScene(Scene):
    """Wordless visual explanation of native versus cross-platform mobile apps."""

    def construct(self):
        self.camera.background_color = BLACK

        CYAN = "#43E6D0"
        BLUE = "#60A5FA"
        GREEN = "#55D98B"
        YELLOW = "#F6C85F"
        RED = "#FF6B6B"
        PURPLE = "#A78BFA"

        PANEL = "#081018"
        PANEL_2 = "#101923"
        LINE = "#33475B"
        WHITE_SOFT = "#E5EEF8"

        flutter_path = BRAND / "flutter.svg"
        android_path = BRAND / "android.svg"
        apple_path = BRAND / "apple.svg"

        def brand_logo(path, color, height=0.48):
            mark = SVGMobject(str(path))
            mark.set_height(height)
            mark.set_fill(color, opacity=1)
            mark.set_stroke(color, width=0, opacity=0)
            return mark

        def phone(color, logo_path=None, logo_color=None, scale=1.0):
            outer = RoundedRectangle(
                width=1.62,
                height=2.92,
                corner_radius=0.24,
                stroke_color=color,
                stroke_width=3.2,
                fill_color=PANEL,
                fill_opacity=1,
            )
            screen = RoundedRectangle(
                width=1.30,
                height=2.42,
                corner_radius=0.13,
                stroke_color=LINE,
                stroke_width=1.4,
                fill_color=PANEL_2,
                fill_opacity=1,
            ).move_to(outer)
            notch = RoundedRectangle(
                width=0.44,
                height=0.08,
                corner_radius=0.04,
                stroke_width=0,
                fill_color=color,
                fill_opacity=0.72,
            ).move_to(outer.get_top() + DOWN * 0.18)
            home = Line(
                LEFT * 0.18,
                RIGHT * 0.18,
                color=LINE,
                stroke_width=2.2,
            ).move_to(outer.get_bottom() + UP * 0.17)
            group = VGroup(outer, screen, notch, home)

            if logo_path is not None:
                mark = brand_logo(
                    logo_path,
                    logo_color or color,
                    height=0.50,
                ).move_to(screen.get_center())
                group.add(mark)

            return group.scale(scale)

        def code_card(color, logo_path):
            card = RoundedRectangle(
                width=1.58,
                height=1.28,
                corner_radius=0.18,
                stroke_color=color,
                stroke_width=2.8,
                fill_color=PANEL,
                fill_opacity=1,
            )
            mark = brand_logo(
                logo_path,
                color,
                height=0.40,
            ).move_to(card.get_center() + UP * 0.23)
            syntax = VGroup(
                Line(LEFT * 0.46, RIGHT * 0.39, color=color, stroke_width=2.5),
                Line(LEFT * 0.31, RIGHT * 0.19, color=color, stroke_width=2.5),
                Line(LEFT * 0.44, RIGHT * 0.02, color=color, stroke_width=2.5),
            ).arrange(
                DOWN,
                buff=0.10,
                aligned_edge=LEFT,
            ).move_to(card.get_center() + DOWN * 0.30)
            return VGroup(card, mark, syntax)

        def panel_frame(color, center):
            return RoundedRectangle(
                width=5.70,
                height=5.65,
                corner_radius=0.25,
                stroke_color=color,
                stroke_width=2.4,
                fill_color=PANEL,
                fill_opacity=0.50,
            ).move_to(center)

        def screen_capture_icon(color):
            frame = RoundedRectangle(
                width=1.34,
                height=0.90,
                corner_radius=0.12,
                stroke_color=color,
                stroke_width=3.2,
                fill_color=PANEL,
                fill_opacity=1,
            )
            corners = VGroup(
                Line(frame.get_corner(UL), frame.get_corner(UL) + RIGHT * 0.17, color=color, stroke_width=3),
                Line(frame.get_corner(UL), frame.get_corner(UL) + DOWN * 0.17, color=color, stroke_width=3),
                Line(frame.get_corner(DR), frame.get_corner(DR) + LEFT * 0.17, color=color, stroke_width=3),
                Line(frame.get_corner(DR), frame.get_corner(DR) + UP * 0.17, color=color, stroke_width=3),
            )
            eye = VGroup(
                ArcBetweenPoints(LEFT * 0.24, RIGHT * 0.24, angle=-PI / 2, color=color, stroke_width=2.5),
                ArcBetweenPoints(LEFT * 0.24, RIGHT * 0.24, angle=PI / 2, color=color, stroke_width=2.5),
                Dot(radius=0.045, color=color),
            ).move_to(frame)
            return VGroup(frame, corners, eye)

        def key_badge(color, logo_path):
            box = RoundedRectangle(
                width=1.72,
                height=1.34,
                corner_radius=0.16,
                stroke_color=color,
                stroke_width=2.8,
                fill_color=PANEL,
                fill_opacity=1,
            )
            mark = brand_logo(logo_path, color, height=0.31).move_to(
                box.get_center() + UP * 0.31
            )
            ring = Circle(
                radius=0.09,
                stroke_color=color,
                stroke_width=2.5,
            ).move_to(box.get_center() + DOWN * 0.25 + LEFT * 0.08)
            shaft = Line(
                ring.get_right(),
                ring.get_right() + RIGHT * 0.24,
                color=color,
                stroke_width=2.5,
            )
            tooth = Line(
                shaft.get_end(),
                shaft.get_end() + DOWN * 0.09,
                color=color,
                stroke_width=2.5,
            )
            return VGroup(box, mark, ring, shaft, tooth)

        def blur_grid(width, height):
            cells = VGroup()
            colors = [BLUE, PURPLE, CYAN, GREEN, YELLOW, RED]
            cols, rows = 6, 5
            cell_w, cell_h = width / cols, height / rows
            for row in range(rows):
                for col in range(cols):
                    cell = Rectangle(
                        width=cell_w + 0.012,
                        height=cell_h + 0.012,
                        stroke_width=0,
                        fill_color=colors[(row * 2 + col) % len(colors)],
                        fill_opacity=0.48 + 0.08 * ((row + col) % 3),
                    )
                    cell.move_to(
                        LEFT * width / 2
                        + RIGHT * (cell_w / 2 + col * cell_w)
                        + UP * height / 2
                        + DOWN * (cell_h / 2 + row * cell_h)
                    )
                    cells.add(cell)
            return cells

        def pulse_ring(mob, color, width=0.13, run_time=0.9):
            ring = Circle(
                radius=max(mob.width, mob.height) * 0.47,
                stroke_color=color,
                stroke_width=3.5,
            ).move_to(mob)
            self.add(ring)
            self.play(
                ring.animate.scale(1.20).set_opacity(0),
                run_time=run_time,
                rate_func=rate_functions.ease_out_expo,
            )
            self.remove(ring)

        # ------------------------------------------------------------
        # Build both visual routes before the first frame.
        # ------------------------------------------------------------
        native_center = LEFT * 3.45 + DOWN * 0.35
        cross_center = RIGHT * 3.45 + DOWN * 0.35

        native_frame = panel_frame(BLUE, native_center)
        cross_frame = panel_frame(PURPLE, cross_center)

        native_android_card = code_card(GREEN, android_path).move_to(
            LEFT * 4.25 + UP * 1.25
        )
        native_apple_card = code_card(CYAN, apple_path).move_to(
            LEFT * 2.65 + UP * 1.25
        )
        native_android_phone = phone(
            GREEN,
            android_path,
            GREEN,
            scale=0.82,
        ).move_to(LEFT * 4.25 + DOWN * 0.95)
        native_apple_phone = phone(
            CYAN,
            apple_path,
            WHITE_SOFT,
            scale=0.82,
        ).move_to(LEFT * 2.65 + DOWN * 0.95)

        native_link_a = Arrow(
            native_android_card.get_bottom(),
            native_android_phone.get_top(),
            buff=0.13,
            color=GREEN,
            stroke_width=3.2,
        )
        native_link_b = Arrow(
            native_apple_card.get_bottom(),
            native_apple_phone.get_top(),
            buff=0.13,
            color=CYAN,
            stroke_width=3.2,
        )

        native_visuals = VGroup(
            native_android_card,
            native_apple_card,
            native_android_phone,
            native_apple_phone,
            native_link_a,
            native_link_b,
        )
        native_group = VGroup(native_frame, native_visuals)

        shared_card = code_card(PURPLE, flutter_path).scale(1.10).move_to(
            RIGHT * 3.45 + UP * 1.20
        )
        cross_android_phone = phone(
            PURPLE,
            android_path,
            GREEN,
            scale=0.82,
        ).move_to(RIGHT * 2.65 + DOWN * 0.95)
        cross_apple_phone = phone(
            PURPLE,
            apple_path,
            WHITE_SOFT,
            scale=0.82,
        ).move_to(RIGHT * 4.25 + DOWN * 0.95)
        cross_link_a = Arrow(
            shared_card.get_bottom(),
            cross_android_phone.get_top(),
            buff=0.13,
            color=PURPLE,
            stroke_width=3.2,
        )
        cross_link_b = Arrow(
            shared_card.get_bottom(),
            cross_apple_phone.get_top(),
            buff=0.13,
            color=PURPLE,
            stroke_width=3.2,
        )
        cross_visuals = VGroup(
            shared_card,
            cross_android_phone,
            cross_apple_phone,
            cross_link_a,
            cross_link_b,
        )
        cross_group = VGroup(cross_frame, cross_visuals)

        # Flutter starts above the two routes; arrows point down into them.
        native_entry = Arrow(
            LEFT * 0.30 + UP * 2.64,
            LEFT * 2.10 + UP * 2.35,
            buff=0.10,
            color=BLUE,
            stroke_width=4,
        )
        cross_entry = Arrow(
            RIGHT * 0.30 + UP * 2.64,
            RIGHT * 2.10 + UP * 2.35,
            buff=0.10,
            color=PURPLE,
            stroke_width=4,
        )

        # ------------------------------------------------------------
        # One language, then two mobile routes.
        # ------------------------------------------------------------
        core_ring = Circle(
            radius=0.56,
            stroke_color=CYAN,
            stroke_width=4,
            fill_color=CYAN,
            fill_opacity=0.08,
        ).move_to(UP * 3.20)
        core_mark = brand_logo(flutter_path, CYAN, height=0.68).move_to(
            core_ring.get_center()
        )
        core = VGroup(core_ring, core_mark)

        self.play(
            GrowFromCenter(core_ring),
            FadeIn(core_mark, scale=0.25),
            run_time=1.35,
        )
        pulse_ring(core, CYAN, run_time=1.0)
        self.wait(0.55)

        self.play(
            GrowArrow(native_entry),
            GrowArrow(cross_entry),
            run_time=1.30,
            rate_func=rate_functions.ease_in_out_sine,
        )
        self.play(
            FadeIn(native_frame, shift=DOWN * 0.12),
            FadeIn(cross_frame, shift=DOWN * 0.12),
            run_time=0.85,
        )
        # Finish the split before revealing either implementation.
        self.play(
            FadeOut(core),
            FadeOut(native_entry),
            FadeOut(cross_entry),
            run_time=0.75,
        )
        self.play(
            LaggedStart(
                FadeIn(native_android_card, scale=0.78),
                FadeIn(native_apple_card, scale=0.78),
                FadeIn(native_android_phone, shift=UP * 0.16),
                FadeIn(native_apple_phone, shift=UP * 0.16),
                GrowArrow(native_link_a),
                GrowArrow(native_link_b),
                lag_ratio=0.22,
            ),
            LaggedStart(
                FadeIn(shared_card, scale=0.78),
                FadeIn(cross_android_phone, shift=UP * 0.16),
                FadeIn(cross_apple_phone, shift=UP * 0.16),
                GrowArrow(cross_link_a),
                GrowArrow(cross_link_b),
                lag_ratio=0.22,
            ),
            run_time=2.15,
        )
        self.wait(0.85)

        # Both routes are legitimate choices.
        self.play(
            native_frame.animate.set_stroke(BLUE, width=5),
            cross_frame.animate.set_stroke(PURPLE, width=5),
            run_time=0.85,
        )
        pulse_ring(native_frame, BLUE, run_time=0.72)
        pulse_ring(cross_frame, PURPLE, run_time=0.72)
        self.wait(0.70)

        # ------------------------------------------------------------
        # The requirement: capture and monitor the screen.
        # ------------------------------------------------------------
        self.play(
            FadeOut(cross_group),
            native_group.animate.scale(0.64).to_edge(
                LEFT,
                buff=0.40,
            ).shift(UP * 0.55),
            run_time=1.20,
            rate_func=rate_functions.ease_in_out_sine,
        )
        self.wait(0.50)

        capture = screen_capture_icon(YELLOW).scale(1.36).move_to(
            RIGHT * 0.20 + UP * 2.05
        )
        self.play(
            FadeIn(capture, scale=0.68),
            run_time=0.90,
        )
        self.wait(0.40)

        scan_line = Line(
            capture.get_left() + RIGHT * 0.10,
            capture.get_right() + LEFT * 0.10,
            color=YELLOW,
            stroke_width=4,
        ).move_to(capture.get_top() + DOWN * 0.17)
        self.add(scan_line)
        self.play(
            scan_line.animate.move_to(capture.get_bottom() + UP * 0.17),
            run_time=1.65,
            rate_func=linear,
        )
        self.remove(scan_line)
        pulse_ring(capture, YELLOW, run_time=0.82)
        self.wait(0.55)

        # Android and iPhone require visibly different handling paths.
        android_permission = key_badge(GREEN, android_path).move_to(
            RIGHT * 0.00 + DOWN * 0.60
        )
        apple_permission = key_badge(CYAN, apple_path).move_to(
            RIGHT * 3.20 + DOWN * 0.60
        )
        capture_to_android = Arrow(
            capture.get_bottom(),
            android_permission.get_top(),
            buff=0.18,
            color=GREEN,
            stroke_width=4,
        )
        capture_to_apple = Arrow(
            capture.get_bottom(),
            apple_permission.get_top(),
            buff=0.18,
            color=CYAN,
            stroke_width=4,
        )
        self.play(
            GrowArrow(capture_to_android),
            GrowArrow(capture_to_apple),
            FadeIn(android_permission, scale=0.72),
            FadeIn(apple_permission, scale=0.72),
            run_time=1.05,
        )
        self.wait(0.45)

        android_layers = VGroup(
            Dot(radius=0.13, color=GREEN, fill_opacity=0.32),
            Dot(radius=0.13, color=GREEN, fill_opacity=0.55),
            Dot(radius=0.13, color=GREEN, fill_opacity=0.85),
        ).arrange(DOWN, buff=0.28).next_to(
            android_permission,
            DOWN,
            buff=0.28,
        )
        apple_layers = VGroup(
            RoundedRectangle(width=0.34, height=0.18, corner_radius=0.05, stroke_color=CYAN, stroke_width=2),
            RoundedRectangle(width=0.52, height=0.18, corner_radius=0.05, stroke_color=CYAN, stroke_width=2),
            RoundedRectangle(width=0.74, height=0.18, corner_radius=0.05, stroke_color=CYAN, stroke_width=2),
        ).arrange(DOWN, buff=0.22).next_to(
            apple_permission,
            DOWN,
            buff=0.28,
        )
        android_path_line = DashedLine(
            android_permission.get_bottom(),
            android_layers.get_top(),
            color=GREEN,
            stroke_width=3,
            dash_length=0.09,
        )
        apple_path_line = ArcBetweenPoints(
            apple_permission.get_bottom(),
            apple_layers.get_top(),
            angle=-0.58,
            color=CYAN,
            stroke_width=3,
        )
        self.play(
            Create(android_path_line),
            Create(apple_path_line),
            LaggedStart(
                *[FadeIn(item, scale=0.25) for item in android_layers],
                lag_ratio=0.20,
            ),
            LaggedStart(
                *[FadeIn(item, scale=0.25) for item in apple_layers],
                lag_ratio=0.20,
            ),
            run_time=1.60,
        )
        self.play(
            ShowPassingFlash(
                android_path_line.copy().set_stroke(GREEN, width=8),
                time_width=0.38,
            ),
            ShowPassingFlash(
                apple_path_line.copy().set_stroke(CYAN, width=8),
                time_width=0.38,
            ),
            run_time=1.25,
        )
        self.wait(0.75)

        # ------------------------------------------------------------
        # A monitored device and the blur decision.
        # ------------------------------------------------------------
        permission_objects = VGroup(
            android_permission,
            apple_permission,
            capture_to_android,
            capture_to_apple,
            android_layers,
            apple_layers,
            android_path_line,
            apple_path_line,
        )
        self.play(
            FadeOut(permission_objects),
            capture.animate.scale(0.68).move_to(LEFT * 0.45 + UP * 0.10),
            run_time=1.10,
            rate_func=rate_functions.ease_in_out_sine,
        )

        monitored_phone = phone(BLUE, scale=1.50).move_to(
            RIGHT * 3.20 + UP * 0.10
        )
        self.play(
            FadeIn(monitored_phone, shift=UP * 0.18),
            run_time=1.05,
        )

        screen = monitored_phone[1]
        content_frame = RoundedRectangle(
            width=1.42,
            height=1.10,
            corner_radius=0.09,
            stroke_color=LINE,
            stroke_width=1.5,
            fill_color="#17202A",
            fill_opacity=1,
        ).move_to(screen.get_center() + UP * 0.28)
        person_head = Circle(
            radius=0.17,
            fill_color=WHITE_SOFT,
            fill_opacity=0.72,
            stroke_width=0,
        ).move_to(content_frame.get_center() + UP * 0.17)
        person_body = RoundedRectangle(
            width=0.56,
            height=0.42,
            corner_radius=0.15,
            fill_color=WHITE_SOFT,
            fill_opacity=0.58,
            stroke_width=0,
        ).next_to(person_head, DOWN, buff=0.04)
        content = VGroup(content_frame, person_head, person_body)

        normal_lines = VGroup(
            Line(LEFT * 0.45, RIGHT * 0.43, color=LINE, stroke_width=3),
            Line(LEFT * 0.34, RIGHT * 0.19, color=LINE, stroke_width=3),
            Line(LEFT * 0.42, RIGHT * 0.36, color=LINE, stroke_width=3),
        ).arrange(DOWN, buff=0.17, aligned_edge=LEFT).move_to(
            screen.get_center() + DOWN * 0.74
        )
        self.play(
            FadeIn(content, scale=0.88),
            FadeIn(normal_lines, shift=UP * 0.10),
            run_time=0.95,
        )
        self.wait(0.65)

        monitor_arrow = Arrow(
            capture.get_right(),
            monitored_phone.get_left(),
            buff=0.25,
            color=YELLOW,
            stroke_width=5,
        )
        self.play(
            GrowArrow(monitor_arrow),
            run_time=0.85,
        )
        packet = Dot(radius=0.11, color=YELLOW).move_to(
            monitor_arrow.get_start()
        )
        self.add(packet)
        self.play(
            MoveAlongPath(
                packet,
                Line(monitor_arrow.get_start(), monitor_arrow.get_end()),
            ),
            run_time=1.15,
            rate_func=linear,
        )
        self.remove(packet)
        self.wait(0.50)

        detect_box = RoundedRectangle(
            width=content_frame.width + 0.12,
            height=content_frame.height + 0.12,
            corner_radius=0.11,
            stroke_color=RED,
            stroke_width=4,
        ).move_to(content_frame)
        self.play(
            Create(detect_box),
            run_time=0.90,
        )
        self.play(
            detect_box.animate.scale(1.08),
            run_time=0.55,
            rate_func=there_and_back,
        )
        self.wait(0.35)

        blurred = blur_grid(
            content_frame.width - 0.06,
            content_frame.height - 0.06,
        ).move_to(content_frame)
        dark_overlay = RoundedRectangle(
            width=content_frame.width,
            height=content_frame.height,
            corner_radius=0.08,
            stroke_width=0,
            fill_color=BLACK,
            fill_opacity=0.26,
        ).move_to(content_frame)
        self.play(
            FadeOut(VGroup(person_head, person_body), run_time=0.65),
            LaggedStart(
                *[FadeIn(cell, scale=0.20) for cell in blurred],
                lag_ratio=0.035,
            ),
            FadeIn(dark_overlay),
            detect_box.animate.set_stroke(GREEN, width=4),
            run_time=2.10,
        )
        self.wait(0.75)

        # Native is the final highlighted route for this screen-sensitive job.
        native_focus = RoundedRectangle(
            width=native_group.width + 0.24,
            height=native_group.height + 0.24,
            corner_radius=0.26,
            stroke_color=GREEN,
            stroke_width=4,
        ).move_to(native_group)
        native_focus.set_opacity(0)
        self.add(native_focus)
        self.play(
            native_focus.animate.set_opacity(1).scale(1.06),
            ShowPassingFlash(
                monitor_arrow.copy().set_stroke(GREEN, width=9),
                time_width=0.34,
            ),
            run_time=1.15,
            rate_func=rate_functions.ease_out_expo,
        )
        self.play(
            native_focus.animate.scale(1.16).set_opacity(0),
            run_time=0.90,
            rate_func=rate_functions.ease_out_expo,
        )
        self.remove(native_focus)
        self.wait(2.20)


class DynamicBlurTrackingScene(Scene):
    def construct(self):
        from PIL import ImageEnhance, ImageOps

        self.camera.background_color = BLACK

        # ============================================================
        # COLORS
        # ============================================================

        CYAN = "#43E6D0"
        BLUE = "#60A5FA"
        GREEN = "#55D98B"
        RED = "#FF5C68"
        YELLOW = "#F6C85F"
        PURPLE = "#A78BFA"

        PANEL = "#070B10"
        SCREEN = "#0B1219"
        CARD = "#101923"
        CARD_2 = "#16212C"

        LINE = "#293847"
        MUTED = "#708294"
        SOFT = "#D7E1EA"

        # ============================================================
        # TIMING
        #
        # Intentionally slower than previous version.
        # ============================================================

        TINY = 0.18
        FAST = 0.35
        NORMAL = 0.65
        SLOW = 0.95
        EXPLAIN = 1.25

        # ============================================================
        # ASSETS
        # ============================================================

        woman_paths = [
            find_file(
                f"woman_{i}.png",
                (
                    "assets",
                    "assets/Image",
                    "Image",
                    "",
                ),
            )
            for i in range(1, 6)
        ]

        # ============================================================
        # BUILD DARK OPAQUE PIXELATED VERSIONS
        # ============================================================

        def build_pixel_blur(
            source,
            pixel_columns=8,
            brightness=0.29,
        ):
            generated_dir = (
                ROOT
                / "_generated"
                / "dark_pixel_blur"
            )

            generated_dir.mkdir(
                parents=True,
                exist_ok=True,
            )

            output = (
                generated_dir
                / f"{source.stem}_px_{pixel_columns}.png"
            )

            if (
                output.exists()
                and output.stat().st_mtime
                >= source.stat().st_mtime
            ):
                return output

            with Image.open(source) as original:
                original = original.convert("RGBA")

                # Remove transparency by compositing on a dark background.
                background = Image.new(
                    "RGBA",
                    original.size,
                    (8, 12, 18, 255),
                )

                background.alpha_composite(
                    original
                )

                image = background.convert("RGB")

                # Large blocks.
                small_width = pixel_columns

                small_height = max(
                    1,
                    round(
                        image.height
                        / image.width
                        * small_width
                    ),
                )

                tiny = image.resize(
                    (
                        small_width,
                        small_height,
                    ),
                    Image.Resampling.BOX,
                )

                pixelated = tiny.resize(
                    image.size,
                    Image.Resampling.NEAREST,
                )

                # Reduce details.
                pixelated = ImageOps.posterize(
                    pixelated,
                    3,
                )

                pixelated = ImageEnhance.Brightness(
                    pixelated
                ).enhance(
                    brightness
                )

                pixelated = ImageEnhance.Contrast(
                    pixelated
                ).enhance(
                    1.18
                )

                pixelated.save(
                    output
                )

            return output

        blur_paths = [
            build_pixel_blur(
                path,
                pixel_columns=8,
                brightness=0.29,
            )
            for path in woman_paths
        ]

        # ============================================================
        # HELPERS
        # ============================================================

        def image_mobject(path, width, height):
            img = ImageMobject(path)
            img.set_width(width)
            img.set_height(height)
            return img

        # ------------------------------------------------------------
        # Warning icon — built only from shapes, no text.
        # ------------------------------------------------------------

        def warning_icon():
            triangle = Triangle(
                stroke_color=RED,
                stroke_width=4,
                fill_color=RED,
                fill_opacity=0.06,
            ).scale(0.48)

            bar = RoundedRectangle(
                width=0.07,
                height=0.26,
                corner_radius=0.025,
                stroke_width=0,
                fill_color=RED,
                fill_opacity=1,
            ).move_to(
                triangle.get_center()
                + UP * 0.08
            )

            dot = Dot(
                radius=0.038,
                color=RED,
            ).move_to(
                triangle.get_center()
                + DOWN * 0.16
            )

            return VGroup(
                triangle,
                bar,
                dot,
            )

        # ------------------------------------------------------------
        # Clock icon for timing.
        # ------------------------------------------------------------

        def clock_icon(color=YELLOW):
            outer = Circle(
                radius=0.32,
                stroke_color=color,
                stroke_width=3,
            )

            hand_1 = Line(
                ORIGIN,
                UP * 0.17,
                color=color,
                stroke_width=3,
            )

            hand_2 = Line(
                ORIGIN,
                RIGHT * 0.12,
                color=color,
                stroke_width=3,
            )

            center = Dot(
                radius=0.035,
                color=color,
            )

            return VGroup(
                outer,
                hand_1,
                hand_2,
                center,
            )

        # ------------------------------------------------------------
        # Vertical movement / scrolling icon.
        # ------------------------------------------------------------

        def scroll_icon(color=CYAN):
            line = Line(
                DOWN * 0.30,
                UP * 0.30,
                color=color,
                stroke_width=3,
            )

            upper = Triangle(
                stroke_color=color,
                stroke_width=2.5,
                fill_color=color,
                fill_opacity=0.15,
            ).scale(0.09)

            upper.move_to(
                line.get_top()
                + UP * 0.07
            )

            lower = upper.copy().rotate(PI)

            lower.move_to(
                line.get_bottom()
                + DOWN * 0.07
            )

            return VGroup(
                line,
                upper,
                lower,
            )

        # ------------------------------------------------------------
        # Search grid complexity icon.
        # ------------------------------------------------------------

        def grid_icon(color=PURPLE):
            rectangles = VGroup(
                RoundedRectangle(
                    width=0.28,
                    height=0.42,
                    corner_radius=0.04,
                    stroke_color=color,
                    stroke_width=2,
                ),
                RoundedRectangle(
                    width=0.34,
                    height=0.24,
                    corner_radius=0.04,
                    stroke_color=color,
                    stroke_width=2,
                ),
                RoundedRectangle(
                    width=0.24,
                    height=0.32,
                    corner_radius=0.04,
                    stroke_color=color,
                    stroke_width=2,
                ),
                RoundedRectangle(
                    width=0.36,
                    height=0.36,
                    corner_radius=0.04,
                    stroke_color=color,
                    stroke_width=2,
                ),
            )

            rectangles[0].move_to(
                LEFT * 0.20
                + UP * 0.18
            )

            rectangles[1].move_to(
                RIGHT * 0.20
                + UP * 0.24
            )

            rectangles[2].move_to(
                LEFT * 0.22
                + DOWN * 0.24
            )

            rectangles[3].move_to(
                RIGHT * 0.20
                + DOWN * 0.20
            )

            return rectangles

        # ------------------------------------------------------------
        # Multiple masks icon.
        # ------------------------------------------------------------

        def layers_icon(color=RED):
            layers = VGroup()

            for i in range(4):
                rect = RoundedRectangle(
                    width=0.48,
                    height=0.30,
                    corner_radius=0.06,
                    stroke_color=color,
                    stroke_width=2,
                    fill_color=color,
                    fill_opacity=0.06,
                )

                rect.shift(
                    RIGHT * 0.07 * i
                    + UP * 0.06 * i
                )

                layers.add(rect)

            return layers

        # ============================================================
        # PHONE
        # ============================================================

        phone_center = RIGHT * 0.75

        phone_body = RoundedRectangle(
            width=3.60,
            height=6.55,
            corner_radius=0.39,
            stroke_color=CYAN,
            stroke_width=3.1,
            fill_color=PANEL,
            fill_opacity=1,
        ).move_to(
            phone_center
        )

        screen = RoundedRectangle(
            width=3.12,
            height=5.90,
            corner_radius=0.23,
            stroke_color=LINE,
            stroke_width=1.1,
            fill_color=SCREEN,
            fill_opacity=1,
        ).move_to(
            phone_body
        )

        phone_outline = phone_body.copy()
        phone_outline.set_fill(opacity=0)
        phone_outline.set_z_index(300)

        dynamic_island = RoundedRectangle(
            width=0.80,
            height=0.12,
            corner_radius=0.06,
            stroke_width=0,
            fill_color=BLACK,
            fill_opacity=1,
        ).move_to(
            phone_body.get_top()
            + DOWN * 0.20
        )

        dynamic_island.set_z_index(310)

        home_indicator = RoundedRectangle(
            width=0.80,
            height=0.055,
            corner_radius=0.025,
            stroke_width=0,
            fill_color=MUTED,
            fill_opacity=0.55,
        ).move_to(
            phone_body.get_bottom()
            + UP * 0.18
        )

        home_indicator.set_z_index(310)

        # ============================================================
        # VIEWPORT MASKS
        # Keep all moving content inside the phone.
        # ============================================================

        top_mask = Rectangle(
            width=4.4,
            height=4,
            stroke_width=0,
            fill_color=BLACK,
            fill_opacity=1,
        )

        top_mask.move_to([
            screen.get_center()[0],
            screen.get_top()[1] + 2,
            0,
        ])

        top_mask.set_z_index(180)

        bottom_mask = Rectangle(
            width=4.4,
            height=4,
            stroke_width=0,
            fill_color=BLACK,
            fill_opacity=1,
        )

        bottom_mask.move_to([
            screen.get_center()[0],
            screen.get_bottom()[1] - 2,
            0,
        ])

        bottom_mask.set_z_index(180)

        # ============================================================
        # APP CHROME
        # ============================================================

        top_bar = RoundedRectangle(
            width=2.98,
            height=0.62,
            corner_radius=0.16,
            stroke_width=0,
            fill_color=SCREEN,
            fill_opacity=1,
        ).move_to(
            screen.get_top()
            + DOWN * 0.38
        )

        top_bar.set_z_index(205)

        avatar = Circle(
            radius=0.10,
            fill_color=BLUE,
            fill_opacity=1,
            stroke_width=0,
        ).move_to(
            top_bar.get_left()
            + RIGHT * 0.25
        )

        avatar.set_z_index(210)

        meta_lines = VGroup(
            Line(
                LEFT * 0.28,
                RIGHT * 0.28,
                color=SOFT,
                stroke_width=2.3,
            ),
            Line(
                LEFT * 0.20,
                RIGHT * 0.10,
                color=MUTED,
                stroke_width=1.7,
            ),
        ).arrange(
            DOWN,
            buff=0.075,
            aligned_edge=LEFT,
        )

        meta_lines.next_to(
            avatar,
            RIGHT,
            buff=0.11,
        )

        meta_lines.set_z_index(210)

        top_icons = VGroup(
            Circle(
                radius=0.09,
                stroke_color=SOFT,
                stroke_width=1.6,
            ),
            Circle(
                radius=0.09,
                stroke_color=SOFT,
                stroke_width=1.6,
            ),
        ).arrange(
            RIGHT,
            buff=0.14,
        )

        top_icons.move_to(
            top_bar.get_right()
            + LEFT * 0.34
        )

        top_icons.set_z_index(210)

        top_chrome = VGroup(
            top_bar,
            avatar,
            meta_lines,
            top_icons,
        )

        bottom_bar = RoundedRectangle(
            width=2.98,
            height=0.58,
            corner_radius=0.15,
            stroke_width=0,
            fill_color=SCREEN,
            fill_opacity=1,
        ).move_to(
            screen.get_bottom()
            + UP * 0.37
        )

        bottom_bar.set_z_index(205)

        nav = VGroup(
            Circle(
                radius=0.09,
                stroke_color=SOFT,
                stroke_width=2,
            ),
            Circle(
                radius=0.09,
                stroke_color=MUTED,
                stroke_width=2,
            ),
            RoundedRectangle(
                width=0.27,
                height=0.20,
                corner_radius=0.05,
                stroke_color=CYAN,
                stroke_width=2,
            ),
            Triangle(
                stroke_color=MUTED,
                stroke_width=2,
            ).scale(0.085).rotate(-PI / 2),
            Circle(
                radius=0.09,
                stroke_color=MUTED,
                stroke_width=2,
            ),
        ).arrange(
            RIGHT,
            buff=0.35,
        )

        nav.move_to(bottom_bar)
        nav.set_z_index(210)

        bottom_chrome = VGroup(
            bottom_bar,
            nav,
        )

        # ============================================================
        # SCREENSHOT ICON
        # ============================================================

        capture_frame = RoundedRectangle(
            width=1.02,
            height=0.77,
            corner_radius=0.10,
            stroke_color=YELLOW,
            stroke_width=2.8,
            fill_color=PANEL,
            fill_opacity=1,
        )

        capture_dot = Dot(
            radius=0.075,
            color=YELLOW,
        ).move_to(
            capture_frame
        )

        capture = VGroup(
            capture_frame,
            capture_dot,
        )

        capture.move_to(
            LEFT * 3.2
            + UP * 1.50
        )

        capture_link = DashedLine(
            capture.get_right(),
            phone_body.get_left()
            + UP * 1.05,
            color=YELLOW,
            stroke_width=2,
            dash_length=0.08,
        )

        # ============================================================
        # EXPLANATION ICONS
        # They appear later when the simple idea becomes complicated.
        # ============================================================

        timing_icon = clock_icon()
        movement_icon = scroll_icon()
        search_complexity_icon = grid_icon()
        stale_layers_icon = layers_icon()

        complexity_icons = VGroup(
            timing_icon,
            movement_icon,
            search_complexity_icon,
            stale_layers_icon,
        )

        complexity_icons.arrange(
            DOWN,
            buff=0.47,
        )

        complexity_icons.move_to(
            LEFT * 4.75
            + DOWN * 0.45
        )

        # ============================================================
        # SCREENSHOT EFFECT
        # ============================================================

        def screenshot():
            flash = RoundedRectangle(
                width=screen.width - 0.05,
                height=screen.height - 0.05,
                corner_radius=0.20,
                stroke_color=WHITE,
                stroke_width=3,
                fill_color=WHITE,
                fill_opacity=0.09,
            ).move_to(screen)

            flash.set_z_index(150)

            self.play(
                FadeIn(flash),
                capture_dot.animate.scale(1.45),
                run_time=0.12,
            )

            self.play(
                FadeOut(flash),
                capture_dot.animate.scale(1 / 1.45),
                run_time=0.12,
            )

        # ============================================================
        # SCAN
        # ============================================================

        def scan(duration=0.75):
            line = Line(
                screen.get_left()
                + RIGHT * 0.12,
                screen.get_right()
                + LEFT * 0.12,
                color=CYAN,
                stroke_width=4,
            )

            line.move_to(
                screen.get_top()
                + DOWN * 0.72
            )

            line.set_z_index(145)

            self.add(line)

            self.play(
                line.animate.move_to(
                    screen.get_bottom()
                    + UP * 0.72
                ),
                run_time=duration,
                rate_func=linear,
            )

            self.remove(line)

        # ============================================================
        # DETECTOR
        # ============================================================

        def detector(target, color=RED):
            box = RoundedRectangle(
                width=target.width + 0.055,
                height=target.height + 0.055,
                corner_radius=0.075,
                stroke_color=color,
                stroke_width=3,
                fill_opacity=0,
            )

            box.move_to(target)
            box.set_z_index(105)

            return box

        # ============================================================
        # PIXEL BLUR
        # ============================================================

        def pixel_blur(index, target):
            blurred = image_mobject(
                blur_paths[index],
                target.width,
                target.height,
            )

            blurred.move_to(target)
            blurred.set_z_index(90)

            dark = RoundedRectangle(
                width=target.width,
                height=target.height,
                corner_radius=0.065,
                stroke_width=0,
                fill_color=BLACK,
                fill_opacity=0.27,
            ).move_to(target)

            dark.set_z_index(91)

            border = RoundedRectangle(
                width=target.width,
                height=target.height,
                corner_radius=0.065,
                stroke_color="#1C2833",
                stroke_width=1,
            ).move_to(target)

            border.set_z_index(92)

            return Group(
                blurred,
                dark,
                border,
            )

        # ============================================================
        # SOCIAL POST
        # ============================================================

        def social_post(index):
            card = RoundedRectangle(
                width=2.67,
                height=2.18,
                corner_radius=0.14,
                stroke_color=LINE,
                stroke_width=1,
                fill_color=CARD,
                fill_opacity=1,
            )

            post_avatar = Circle(
                radius=0.072,
                stroke_width=0,
                fill_color=BLUE,
                fill_opacity=1,
            )

            lines = VGroup(
                Line(
                    LEFT * 0.29,
                    RIGHT * 0.29,
                    color=SOFT,
                    stroke_width=2.1,
                ),
                Line(
                    LEFT * 0.20,
                    RIGHT * 0.11,
                    color=MUTED,
                    stroke_width=1.6,
                ),
            ).arrange(
                DOWN,
                buff=0.07,
                aligned_edge=LEFT,
            )

            header = VGroup(
                post_avatar,
                lines,
            ).arrange(
                RIGHT,
                buff=0.10,
            )

            header.move_to(
                card.get_top()
                + DOWN * 0.19
                + LEFT * 0.62
            )

            image = image_mobject(
                woman_paths[index],
                2.39,
                1.42,
            )

            image.move_to(
                card.get_center()
                + UP * 0.02
            )

            image_frame = RoundedRectangle(
                width=2.39,
                height=1.42,
                corner_radius=0.075,
                stroke_color=LINE,
                stroke_width=1,
            ).move_to(image)

            actions = VGroup(
                Circle(
                    radius=0.05,
                    stroke_color=MUTED,
                    stroke_width=1.4,
                ),
                RoundedRectangle(
                    width=0.15,
                    height=0.11,
                    corner_radius=0.035,
                    stroke_color=MUTED,
                    stroke_width=1.4,
                ),
                Triangle(
                    stroke_color=MUTED,
                    stroke_width=1.4,
                ).scale(0.06).rotate(-PI / 2),
            ).arrange(
                RIGHT,
                buff=0.20,
            )

            actions.move_to(
                card.get_bottom()
                + UP * 0.18
                + LEFT * 0.68
            )

            group = Group(
                card,
                header,
                image,
                image_frame,
                actions,
            )

            group.image = image

            return group

        # ============================================================
        # REEL
        # ============================================================

        def reel(index):
            container = RoundedRectangle(
                width=2.72,
                height=4.72,
                corner_radius=0.16,
                stroke_color=LINE,
                stroke_width=1,
                fill_color=BLACK,
                fill_opacity=1,
            )

            image = image_mobject(
                woman_paths[index],
                2.54,
                4.47,
            ).move_to(
                container
            )

            side_icons = VGroup(
                Circle(
                    radius=0.082,
                    stroke_color=WHITE,
                    stroke_width=1.7,
                ),
                Circle(
                    radius=0.082,
                    stroke_color=WHITE,
                    stroke_width=1.7,
                ),
                Circle(
                    radius=0.082,
                    stroke_color=WHITE,
                    stroke_width=1.7,
                ),
            ).arrange(
                DOWN,
                buff=0.19,
            )

            side_icons.move_to(
                container.get_right()
                + LEFT * 0.19
                + DOWN * 1.15
            )

            bottom_avatar = Circle(
                radius=0.085,
                stroke_width=0,
                fill_color=WHITE,
                fill_opacity=0.75,
            )

            bottom_lines = VGroup(
                Line(
                    LEFT * 0.34,
                    RIGHT * 0.34,
                    color=WHITE,
                    stroke_width=2,
                ),
                Line(
                    LEFT * 0.25,
                    RIGHT * 0.13,
                    color=WHITE,
                    stroke_width=1.5,
                ),
            ).arrange(
                DOWN,
                buff=0.07,
                aligned_edge=LEFT,
            )

            information = VGroup(
                bottom_avatar,
                bottom_lines,
            ).arrange(
                RIGHT,
                buff=0.10,
            )

            information.move_to(
                container.get_bottom()
                + UP * 0.34
                + LEFT * 0.52
            )

            group = Group(
                container,
                image,
                side_icons,
                information,
            )

            group.image = image

            return group

        # ============================================================
        # START PHONE
        # ============================================================

        self.play(
            FadeIn(phone_body),
            FadeIn(screen),
            run_time=NORMAL,
        )

        self.add(
            top_mask,
            bottom_mask,
            phone_outline,
            dynamic_island,
            home_indicator,
        )

        self.play(
            FadeIn(top_chrome),
            FadeIn(bottom_chrome),
            run_time=NORMAL,
        )

        self.wait(0.4)

        # ============================================================
        # PART 1
        #
        # THE IDEA LOOKS SIMPLE
        #
        # screenshot -> detect -> blur
        # ============================================================

        self.play(
            FadeIn(capture, scale=0.8),
            Create(capture_link),
            run_time=NORMAL,
        )

        post1 = social_post(0)

        post1.move_to(
            screen.get_center()
            + UP * 0.55
        )

        self.play(
            FadeIn(
                post1,
                shift=UP * 0.10,
            ),
            run_time=SLOW,
        )

        self.wait(0.35)

        # Screenshot.
        screenshot()

        self.wait(0.25)

        # Scan.
        scan(0.85)

        self.wait(0.25)

        # Detection.
        box1 = detector(post1.image)

        self.play(
            Create(box1),
            run_time=NORMAL,
        )

        self.wait(0.25)

        # Blur.
        blur1 = pixel_blur(
            0,
            post1.image,
        )

        self.play(
            FadeIn(blur1),
            box1.animate.set_stroke(
                GREEN,
                width=3.2,
            ),
            run_time=NORMAL,
        )

        self.wait(0.9)

        # ------------------------------------------------------------
        # Repeat screenshot once more to visually imply periodic capture.
        # ------------------------------------------------------------

        screenshot()

        self.wait(0.55)

        # ============================================================
        # "BUT IT IS NOT THAT SIMPLE"
        #
        # No text:
        # warning sign + simple pipeline expands into many problems.
        # ============================================================

        warning = warning_icon()

        warning.move_to(
            LEFT * 3.35
            + DOWN * 0.20
        )

        self.play(
            FadeIn(
                warning,
                scale=0.55,
            ),
            run_time=NORMAL,
        )

        self.play(
            warning.animate.scale(1.15),
            run_time=0.30,
            rate_func=there_and_back,
        )

        self.wait(0.55)

        # Complexity branches appear one by one.
        complexity_lines = VGroup()

        for icon in complexity_icons:
            complexity_lines.add(
                Line(
                    warning.get_center(),
                    icon.get_center(),
                    color=LINE,
                    stroke_width=2,
                )
            )

        self.play(
            Create(complexity_lines[0]),
            FadeIn(timing_icon, scale=0.6),
            run_time=NORMAL,
        )

        self.wait(0.30)

        self.play(
            Create(complexity_lines[1]),
            FadeIn(movement_icon, scale=0.6),
            run_time=NORMAL,
        )

        self.wait(0.30)

        self.play(
            Create(complexity_lines[2]),
            FadeIn(search_complexity_icon, scale=0.6),
            run_time=NORMAL,
        )

        self.wait(0.30)

        self.play(
            Create(complexity_lines[3]),
            FadeIn(stale_layers_icon, scale=0.6),
            run_time=NORMAL,
        )

        self.wait(1.0)

        # ============================================================
        # PART 2
        #
        # TWITTER-LIKE FEED
        #
        # Need to know when blur enters/leaves.
        # ============================================================

        self.play(
            FadeOut(warning),
            FadeOut(complexity_lines),
            timing_icon.animate.set_opacity(0.25),
            search_complexity_icon.animate.set_opacity(0.25),
            stale_layers_icon.animate.set_opacity(0.25),
            movement_icon.animate.scale(1.15),
            run_time=NORMAL,
        )

        post2 = social_post(1)

        post2.move_to(
            post1.get_center()
            + DOWN * 2.30
        )

        feed = Group(
            post1,
            post2,
        )

        self.add(post2)

        self.wait(0.45)

        # Slow realistic scroll.
        self.play(
            feed.animate.shift(
                UP * 0.85
            ),
            box1.animate.shift(
                UP * 0.85
            ),
            blur1.animate.shift(
                UP * 0.85
            ),
            run_time=SLOW,
            rate_func=rate_functions.ease_in_out_cubic,
        )

        self.wait(0.35)

        screenshot()

        box2 = detector(
            post2.image
        )

        self.play(
            Create(box2),
            run_time=NORMAL,
        )

        blur2 = pixel_blur(
            1,
            post2.image,
        )

        self.play(
            FadeIn(blur2),
            box2.animate.set_stroke(
                GREEN
            ),
            run_time=NORMAL,
        )

        self.wait(0.65)

        # ============================================================
        # Show the timing problem:
        # image leaves, but blur remains.
        # ============================================================

        self.play(
            feed.animate.shift(
                UP * 1.00
            ),
            box1.animate.shift(
                UP * 1.00
            ),
            blur1.animate.shift(
                UP * 1.00
            ),
            box2.animate.shift(
                UP * 1.00
            ),
            blur2.animate.shift(
                UP * 1.00
            ),
            run_time=0.80,
            rate_func=rate_functions.ease_in_out_cubic,
        )

        self.wait(0.25)

        # Make a wrong stale mask.
        stale_blur = blur1.copy()

        stale_blur.set_opacity(
            0.78
        )

        stale_blur.move_to(
            screen.get_center()
            + UP * 1.05
        )

        stale_box = detector(
            stale_blur,
            RED,
        )

        self.add(
            stale_blur
        )

        self.play(
            Create(stale_box),
            run_time=NORMAL,
        )

        # Timing icon reacts.
        self.play(
            timing_icon.animate
            .set_opacity(1)
            .scale(1.20),
            run_time=FAST,
        )

        self.play(
            timing_icon.animate.scale(
                1 / 1.20
            ),
            run_time=FAST,
        )

        self.wait(0.8)

        # Fix stale blur.
        self.play(
            FadeOut(stale_blur),
            FadeOut(stale_box),
            FadeOut(post1),
            FadeOut(box1),
            FadeOut(blur1),
            run_time=NORMAL,
        )

        self.wait(0.5)

        # Clean current feed.
        self.play(
            FadeOut(post2),
            FadeOut(box2),
            FadeOut(blur2),
            run_time=NORMAL,
        )

        self.remove(
            feed,
            post1,
            post2,
            box1,
            box2,
            blur1,
            blur2,
        )

        # ============================================================
        # PART 3
        #
        # REELS:
        # Same problem but much faster.
        # ============================================================

        self.play(
            movement_icon.animate.set_opacity(1),
            timing_icon.animate.set_opacity(1),
            search_complexity_icon.animate.set_opacity(0.22),
            stale_layers_icon.animate.set_opacity(0.22),
            run_time=NORMAL,
        )

        reel1 = reel(2)

        reel1.move_to(
            screen.get_center()
            + DOWN * 0.04
        )

        self.play(
            FadeIn(reel1),
            run_time=SLOW,
        )

        screenshot()

        reel_box1 = detector(
            reel1.image
        )

        reel_blur1 = pixel_blur(
            2,
            reel1.image,
        )

        self.play(
            Create(reel_box1),
            run_time=FAST,
        )

        self.play(
            FadeIn(reel_blur1),
            reel_box1.animate.set_stroke(GREEN),
            run_time=NORMAL,
        )

        self.wait(0.7)

        # ------------------------------------------------------------
        # Next Reel
        # ------------------------------------------------------------

        reel2 = reel(3)

        reel2.move_to(
            screen.get_center()
            + DOWN * 5.10
        )

        self.add(reel2)

        self.play(
            reel1.animate.shift(
                UP * 5.10
            ),
            reel2.animate.shift(
                UP * 5.10
            ),
            reel_box1.animate.shift(
                UP * 5.10
            ),
            reel_blur1.animate.shift(
                UP * 5.10
            ),
            run_time=0.75,
            rate_func=rate_functions.ease_in_out_cubic,
        )

        self.remove(
            reel1,
            reel_box1,
            reel_blur1,
        )

        self.wait(0.25)

        reel_box2 = detector(
            reel2.image
        )

        reel_blur2 = pixel_blur(
            3,
            reel2.image,
        )

        self.play(
            Create(reel_box2),
            FadeIn(reel_blur2),
            run_time=NORMAL,
        )

        self.wait(0.55)

        # ------------------------------------------------------------
        # Show incorrect removal range.
        # Blur from prior frame overlaps current frame.
        # ------------------------------------------------------------

        ghost_reel = reel_blur2.copy()

        ghost_reel.scale(0.72)

        ghost_reel.move_to(
            screen.get_center()
            + UP * 1.42
        )

        ghost_reel.set_opacity(
            0.58
        )

        ghost_reel_box = detector(
            ghost_reel,
            RED,
        )

        self.play(
            FadeIn(ghost_reel),
            Create(ghost_reel_box),
            run_time=NORMAL,
        )

        self.wait(0.85)

        self.play(
            FadeOut(ghost_reel),
            FadeOut(ghost_reel_box),
            run_time=NORMAL,
        )

        # ------------------------------------------------------------
        # Another reel, faster.
        # ------------------------------------------------------------

        reel3 = reel(4)

        reel3.move_to(
            screen.get_center()
            + DOWN * 5.10
        )

        self.add(reel3)

        self.play(
            reel2.animate.shift(
                UP * 5.10
            ),
            reel3.animate.shift(
                UP * 5.10
            ),
            reel_box2.animate.shift(
                UP * 5.10
            ),
            reel_blur2.animate.shift(
                UP * 5.10
            ),
            run_time=0.55,
            rate_func=linear,
        )

        self.remove(
            reel2,
            reel_box2,
            reel_blur2,
        )

        reel_box3 = detector(
            reel3.image,
            GREEN,
        )

        reel_blur3 = pixel_blur(
            4,
            reel3.image,
        )

        self.play(
            Create(reel_box3),
            FadeIn(reel_blur3),
            run_time=NORMAL,
        )

        self.wait(0.85)

        self.play(
            FadeOut(reel3),
            FadeOut(reel_box3),
            FadeOut(reel_blur3),
            run_time=NORMAL,
        )

        self.remove(
            reel1,
            reel2,
            reel3,
            reel_box1,
            reel_box2,
            reel_box3,
            reel_blur1,
            reel_blur2,
            reel_blur3,
        )

        # ============================================================
        # PART 4
        #
        # IMAGE SEARCH:
        # Many images, many sizes, each needs its own mask.
        # ============================================================

        self.play(
            search_complexity_icon.animate
            .set_opacity(1)
            .scale(1.18),
            movement_icon.animate.set_opacity(0.24),
            timing_icon.animate.set_opacity(0.30),
            stale_layers_icon.animate.set_opacity(0.30),
            run_time=NORMAL,
        )

        self.play(
            search_complexity_icon.animate.scale(
                1 / 1.18
            ),
            run_time=FAST,
        )

        # Search-like top field.
        self.play(
            FadeOut(avatar),
            FadeOut(meta_lines),
            FadeOut(top_icons),
            run_time=FAST,
        )

        search_field = RoundedRectangle(
            width=2.55,
            height=0.38,
            corner_radius=0.19,
            stroke_color=LINE,
            stroke_width=1,
            fill_color=CARD_2,
            fill_opacity=1,
        ).move_to(
            top_bar
        )

        search_field.set_z_index(210)

        magnifier = VGroup(
            Circle(
                radius=0.073,
                stroke_color=MUTED,
                stroke_width=2,
            ),
            Line(
                ORIGIN,
                RIGHT * 0.085
                + DOWN * 0.085,
                color=MUTED,
                stroke_width=2,
            ).shift(
                RIGHT * 0.06
                + DOWN * 0.06
            ),
        )

        magnifier.move_to(
            search_field.get_left()
            + RIGHT * 0.23
        )

        magnifier.set_z_index(215)

        self.play(
            FadeIn(search_field),
            FadeIn(magnifier),
            run_time=NORMAL,
        )

        # ============================================================
        # FIVE DIFFERENT IMAGE DIMENSIONS
        # ============================================================

        img1 = image_mobject(
            woman_paths[0],
            1.22,
            1.48,
        )

        img2 = image_mobject(
            woman_paths[1],
            1.22,
            0.98,
        )

        img3 = image_mobject(
            woman_paths[2],
            1.22,
            1.38,
        )

        img4 = image_mobject(
            woman_paths[3],
            1.22,
            1.13,
        )

        img5 = image_mobject(
            woman_paths[4],
            1.22,
            1.30,
        )

        left_x = (
            screen.get_center()[0]
            - 0.67
        )

        right_x = (
            screen.get_center()[0]
            + 0.67
        )

        img1.move_to([
            left_x,
            screen.get_center()[1] + 1.38,
            0,
        ])

        img2.move_to([
            right_x,
            screen.get_center()[1] + 1.60,
            0,
        ])

        img3.move_to([
            right_x,
            screen.get_center()[1] + 0.24,
            0,
        ])

        img4.move_to([
            left_x,
            screen.get_center()[1] - 0.25,
            0,
        ])

        img5.move_to([
            right_x,
            screen.get_center()[1] - 1.30,
            0,
        ])

        search_images = Group(
            img1,
            img2,
            img3,
            img4,
            img5,
        )

        search_frames = VGroup()

        for image in search_images:
            search_frames.add(
                RoundedRectangle(
                    width=image.width + 0.025,
                    height=image.height + 0.025,
                    corner_radius=0.06,
                    stroke_color=LINE,
                    stroke_width=1,
                ).move_to(image)
            )

        self.play(
            LaggedStart(
                *[
                    FadeIn(
                        image,
                        scale=0.96,
                    )
                    for image in search_images
                ],
                lag_ratio=0.12,
            ),
            LaggedStart(
                *[
                    Create(frame)
                    for frame in search_frames
                ],
                lag_ratio=0.12,
            ),
            run_time=EXPLAIN,
        )

        self.wait(0.55)

        screenshot()

        scan(0.95)

        # ============================================================
        # EACH IMAGE REQUIRES ITS OWN BOUNDING BOX
        # ============================================================

        search_boxes = VGroup(
            *[
                detector(image)
                for image in search_images
            ]
        )

        self.play(
            LaggedStart(
                *[
                    Create(box)
                    for box in search_boxes
                ],
                lag_ratio=0.16,
            ),
            run_time=EXPLAIN,
        )

        self.wait(0.45)

        search_blurs = Group(
            *[
                pixel_blur(
                    i,
                    search_images[i],
                )
                for i in range(5)
            ]
        )

        self.play(
            LaggedStart(
                *[
                    FadeIn(blur)
                    for blur in search_blurs
                ],
                lag_ratio=0.15,
            ),
            *[
                box.animate.set_stroke(
                    GREEN,
                    width=2.7,
                )
                for box in search_boxes
            ],
            run_time=EXPLAIN,
        )

        self.wait(1.0)

        # ============================================================
        # SCROLL SEARCH RESULTS
        #
        # Blurs must move with different-sized images.
        # ============================================================

        search_content = Group(
            search_images,
            search_frames,
        )

        self.play(
            movement_icon.animate.set_opacity(1),
            timing_icon.animate.set_opacity(0.70),
            run_time=FAST,
        )

        self.play(
            search_content.animate.shift(
                UP * 0.75
            ),
            search_boxes.animate.shift(
                UP * 0.75
            ),
            search_blurs.animate.shift(
                UP * 0.75
            ),
            run_time=SLOW,
            rate_func=rate_functions.ease_in_out_cubic,
        )

        self.wait(0.4)

        self.play(
            search_content.animate.shift(
                UP * 0.55
            ),
            search_boxes.animate.shift(
                UP * 0.55
            ),
            search_blurs.animate.shift(
                UP * 0.55
            ),
            run_time=NORMAL,
        )

        # First image has left.
        self.play(
            FadeOut(img1),
            FadeOut(search_frames[0]),
            FadeOut(search_boxes[0]),
            FadeOut(search_blurs[0]),
            run_time=FAST,
        )

        self.wait(0.65)

        # ============================================================
        # PART 5
        #
        # FAST SCROLL:
        # FOUR OR FIVE OLD BLURS CAN DESTROY THE SCREEN.
        # ============================================================

        self.play(
            stale_layers_icon.animate
            .set_opacity(1)
            .scale(1.25),
            run_time=NORMAL,
        )

        self.play(
            stale_layers_icon.animate.scale(
                1 / 1.25
            ),
            run_time=FAST,
        )

        # Fast scroll.
        self.play(
            search_content.animate.shift(
                UP * 0.95
            ),
            search_boxes.animate.shift(
                UP * 0.95
            ),
            search_blurs.animate.shift(
                UP * 0.95
            ),
            run_time=0.48,
            rate_func=linear,
        )

        # ============================================================
        # Create four stale masks.
        # ============================================================

        ghost1 = search_blurs[1].copy()
        ghost2 = search_blurs[2].copy()
        ghost3 = search_blurs[3].copy()
        ghost4 = search_blurs[4].copy()

        for ghost in (
            ghost1,
            ghost2,
            ghost3,
            ghost4,
        ):
            ghost.scale(0.72)
            ghost.set_opacity(0.82)

        ghost1.move_to(
            screen.get_center()
            + UP * 1.65
        )

        ghost2.move_to(
            screen.get_center()
            + UP * 0.55
        )

        ghost3.move_to(
            screen.get_center()
            + DOWN * 0.55
        )

        ghost4.move_to(
            screen.get_center()
            + DOWN * 1.60
        )

        ghosts = Group(
            ghost1,
            ghost2,
            ghost3,
            ghost4,
        )

        self.play(
            LaggedStart(
                FadeIn(ghost1),
                FadeIn(ghost2),
                FadeIn(ghost3),
                FadeIn(ghost4),
                lag_ratio=0.18,
            ),
            run_time=EXPLAIN,
        )

        self.wait(0.55)

        broken_border = RoundedRectangle(
            width=screen.width - 0.08,
            height=screen.height - 0.08,
            corner_radius=0.20,
            stroke_color=RED,
            stroke_width=4,
            fill_color=RED,
            fill_opacity=0.02,
        ).move_to(screen)

        broken_border.set_z_index(145)

        self.play(
            Create(broken_border),
            phone_outline.animate.set_stroke(
                RED,
                width=4,
            ),
            run_time=NORMAL,
        )

        self.wait(0.55)

        # Masks conflict with each other.
        self.play(
            ghost1.animate.set_opacity(0.25),
            ghost2.animate.set_opacity(0.90),
            ghost3.animate.set_opacity(0.30),
            ghost4.animate.set_opacity(0.90),
            run_time=0.45,
        )

        self.play(
            ghost1.animate.set_opacity(0.90),
            ghost2.animate.set_opacity(0.28),
            ghost3.animate.set_opacity(0.88),
            ghost4.animate.set_opacity(0.25),
            run_time=0.45,
        )

        self.wait(0.85)

        # ============================================================
        # VISUAL COMPLEXITY SUMMARY
        #
        # This is the "it isn't simple" payoff:
        # timing + motion + sizes + stale masks are all active.
        # ============================================================

        self.play(
            timing_icon.animate.set_opacity(1),
            movement_icon.animate.set_opacity(1),
            search_complexity_icon.animate.set_opacity(1),
            stale_layers_icon.animate.set_opacity(1),
            run_time=NORMAL,
        )

        for icon in complexity_icons:
            ring = Circle(
                radius=max(
                    icon.width,
                    icon.height,
                ) * 0.70,
                stroke_color=YELLOW,
                stroke_width=2.5,
            ).move_to(icon)

            self.add(ring)

            self.play(
                ring.animate
                .scale(1.35)
                .set_opacity(0),
                run_time=0.30,
            )

            self.remove(ring)

        self.wait(0.65)

        # ============================================================
        # CLEAN BROKEN STATE
        # ============================================================

        self.play(
            FadeOut(ghosts),
            FadeOut(broken_border),
            FadeOut(search_images),
            FadeOut(search_frames),
            FadeOut(search_boxes),
            FadeOut(search_blurs),
            phone_outline.animate.set_stroke(
                CYAN,
                width=3.1,
            ),
            run_time=SLOW,
        )

        self.remove(
            search_content,
            search_images,
            search_frames,
            search_boxes,
            search_blurs,
            ghosts,
        )

        # ============================================================
        # RESTORE NORMAL FEED HEADER
        # ============================================================

        self.play(
            FadeOut(search_field),
            FadeOut(magnifier),
            FadeIn(avatar),
            FadeIn(meta_lines),
            FadeIn(top_icons),
            run_time=NORMAL,
        )

        # ============================================================
        # FINAL CORRECT EXAMPLE
        #
        # Show what good logic must actually do:
        #
        # detect -> blur
        # track exact position
        # remove exactly when target exits
        # ============================================================

        final_post = social_post(
            4
        )

        final_post.move_to(
            screen.get_center()
            + DOWN * 2.10
        )

        self.add(final_post)

        self.play(
            final_post.animate.shift(
                UP * 2.10
            ),
            run_time=SLOW,
            rate_func=rate_functions.ease_out_cubic,
        )

        self.wait(0.35)

        screenshot()

        final_box = detector(
            final_post.image,
            GREEN,
        )

        final_blur = pixel_blur(
            4,
            final_post.image,
        )

        self.play(
            Create(final_box),
            run_time=NORMAL,
        )

        self.play(
            FadeIn(final_blur),
            run_time=NORMAL,
        )

        self.wait(0.75)

        # Slow tracking.
        self.play(
            final_post.animate.shift(
                UP * 0.55
            ),
            final_box.animate.shift(
                UP * 0.55
            ),
            final_blur.animate.shift(
                UP * 0.55
            ),
            run_time=SLOW,
            rate_func=smooth,
        )

        self.wait(0.35)

        # Faster tracking.
        self.play(
            final_post.animate.shift(
                UP * 0.78
            ),
            final_box.animate.shift(
                UP * 0.78
            ),
            final_blur.animate.shift(
                UP * 0.78
            ),
            run_time=NORMAL,
        )

        self.wait(0.30)

        # Near viewport edge.
        self.play(
            final_post.animate.shift(
                UP * 0.78
            ),
            final_box.animate.shift(
                UP * 0.78
            ),
            final_blur.animate.shift(
                UP * 0.78
            ),
            run_time=NORMAL,
        )

        self.wait(0.25)

        # Correct cleanup:
        # image and blur disappear together.
        self.play(
            final_post.animate.shift(
                UP * 0.65
            ),
            final_box.animate.shift(
                UP * 0.65
            ),
            final_blur.animate.shift(
                UP * 0.65
            ),
            run_time=FAST,
        )

        self.play(
            FadeOut(final_post),
            FadeOut(final_box),
            FadeOut(final_blur),
            run_time=NORMAL,
        )

        self.remove(
            final_post,
            final_box,
            final_blur,
        )

        # ============================================================
        # FINAL VISUAL:
        # Complexity icons connect back to the phone.
        #
        # Visually says:
        # the blur itself is easy;
        # managing all these states is the hard part.
        # ============================================================

        final_connections = VGroup()

        for icon in complexity_icons:
            final_connections.add(
                DashedLine(
                    icon.get_right(),
                    phone_body.get_left(),
                    dash_length=0.07,
                    color=GREEN,
                    stroke_width=1.8,
                )
            )

        self.play(
            LaggedStart(
                *[
                    Create(line)
                    for line in final_connections
                ],
                lag_ratio=0.12,
            ),
            run_time=EXPLAIN,
        )

        self.wait(0.4)

        success = RoundedRectangle(
            width=screen.width - 0.08,
            height=screen.height - 0.08,
            corner_radius=0.20,
            stroke_color=GREEN,
            stroke_width=3.5,
            fill_opacity=0,
        ).move_to(screen)

        success.set_z_index(145)

        self.play(
            FadeIn(success),
            phone_outline.animate.set_stroke(
                GREEN,
                width=3.5,
            ),
            run_time=NORMAL,
        )

        final_wave = Circle(
            radius=0.16,
            stroke_color=GREEN,
            stroke_width=4,
        ).move_to(screen)

        final_wave.set_z_index(150)

        self.add(final_wave)

        self.play(
            final_wave.animate
            .scale(15)
            .set_opacity(0),
            run_time=0.65,
            rate_func=rate_functions.ease_out_expo,
        )

        self.remove(final_wave)

        self.wait(2.2)

class SearchThumbnailLimitationScene(Scene):
    """Wordless scene: a model trained on one photo struggles with a search grid."""

    def construct(self):
        self.camera.background_color = BLACK

        BLUE = "#60A5FA"
        CYAN = "#48E2D0"
        GREEN = "#59DE93"
        PURPLE = "#B59AFF"
        YELLOW = "#F7C55C"
        RED = "#FF6D73"
        WHITE_SOFT = "#E8F0FA"
        PANEL = "#09121C"
        PANEL_2 = "#111D2A"
        MUTED = "#2B3B4C"

        google_path = BRAND / "google.svg"
        web_dir = ROOT / "assets" / "web_images"
        perfume_pd_path = web_dir / "perfume_bottle_pd.jpg"
        perfume_met_path = web_dir / "perfume_bottle_met.jpg"
        avatar_path = web_dir / "avatar_unsplash.jpg"
        fashion_path = web_dir / "fashion_unsplash.jpg"
        landscape_path = web_dir / "landscape_unsplash.jpg"
        plants_path = web_dir / "plants_unsplash.jpg"

        def line(start, end, color, width=3, opacity=1):
            return Line(start, end, color=color, stroke_width=max(width, 4.4), stroke_opacity=opacity)

        def soft_panel(width, height, color, opacity=0.30, radius=0.24):
            return RoundedRectangle(
                width=width,
                height=height,
                corner_radius=radius,
                stroke_color=color,
                stroke_width=2.4,
                fill_color=PANEL,
                fill_opacity=opacity,
            )

        def pulse(mob, color, run_time=0.65, factor=1.18):
            ring = RoundedRectangle(
                width=max(mob.width, 0.6),
                height=max(mob.height, 0.6),
                corner_radius=0.20,
                stroke_color=color,
                stroke_width=3.2,
            ).move_to(mob)
            self.add(ring)
            self.play(
                ring.animate.scale(factor).set_opacity(0),
                run_time=run_time,
                rate_func=rate_functions.ease_out_expo,
            )
            self.remove(ring)

        def travelling_dot(path, color, run_time=0.75):
            dot = Dot(radius=0.075, color=color).move_to(path.get_start())
            self.add(dot)
            self.play(MoveAlongPath(dot, path), run_time=run_time, rate_func=linear)
            self.remove(dot)

        def photo_asset(path, width, height):
            image = ImageMobject(str(path))
            image.stretch_to_fit_width(width)
            image.stretch_to_fit_height(height)
            return image

        def perfume(color=PURPLE, scale=1.0):
            glass = RoundedRectangle(
                width=1.05,
                height=1.42,
                corner_radius=0.18,
                stroke_color=color,
                stroke_width=2.6,
                fill_color="#142131",
                fill_opacity=1,
            )
            liquid = RoundedRectangle(
                width=0.79,
                height=0.55,
                corner_radius=0.09,
                stroke_width=0,
                fill_color=color,
                fill_opacity=0.28,
            ).move_to(glass.get_bottom() + UP * 0.38)
            neck = RoundedRectangle(
                width=0.38,
                height=0.26,
                corner_radius=0.05,
                stroke_color=color,
                stroke_width=2.1,
                fill_color=PANEL_2,
                fill_opacity=1,
            ).move_to(glass.get_top() + UP * 0.13)
            cap = RoundedRectangle(
                width=0.56,
                height=0.25,
                corner_radius=0.06,
                stroke_color=WHITE_SOFT,
                stroke_width=1.8,
                fill_color="#263850",
                fill_opacity=1,
            ).move_to(neck.get_top() + UP * 0.14)
            label = RoundedRectangle(
                width=0.52,
                height=0.34,
                corner_radius=0.05,
                stroke_color=color,
                stroke_width=1.5,
                fill_color=PANEL,
                fill_opacity=0.85,
            ).move_to(glass.get_center() + DOWN * 0.04)
            gleam = line(UP * 0.40, DOWN * 0.23, WHITE_SOFT, 2, 0.55).move_to(
                glass.get_left() + RIGHT * 0.18 + UP * 0.03
            )
            atom = VGroup(
                Circle(radius=0.055, stroke_color=color, stroke_width=1.4),
                line(LEFT * 0.09, RIGHT * 0.09, color, 1.4),
                line(UP * 0.09, DOWN * 0.09, color, 1.4),
            ).move_to(label)
            return VGroup(glass, liquid, neck, cap, label, gleam, atom).scale(scale)

        def target(color, width=1.25, height=1.55):
            corners = VGroup()
            h, v = width * 0.22, height * 0.16
            for sx, sy in ((-1, 1), (1, 1), (-1, -1), (1, -1)):
                anchor = np.array([sx * width / 2, sy * height / 2, 0])
                corners.add(line(anchor, anchor + RIGHT * sx * -h, color, 3.4))
                corners.add(line(anchor, anchor + UP * sy * -v, color, 3.4))
            return corners

        def neural_network(color, scale=1.0):
            positions = [
                [LEFT * 0.55 + UP * 0.28, LEFT * 0.55, LEFT * 0.55 + DOWN * 0.28],
                [UP * 0.42, UP * 0.14, DOWN * 0.14, DOWN * 0.42],
                [RIGHT * 0.55 + UP * 0.28, RIGHT * 0.55, RIGHT * 0.55 + DOWN * 0.28],
            ]
            nodes = VGroup(*[Dot(point=p, radius=0.055, color=color) for col in positions for p in col])
            links = VGroup()
            for a in nodes[:3]:
                for b in nodes[3:7]:
                    links.add(line(a.get_center(), b.get_center(), color, 1.2, 0.42))
            for a in nodes[3:7]:
                for b in nodes[7:]:
                    links.add(line(a.get_center(), b.get_center(), color, 1.2, 0.42))
            return VGroup(links, nodes).scale(scale)

        def model_card(scale=1.0):
            box = soft_panel(2.50, 2.18, GREEN, 0.65, 0.22)
            hexagon = RegularPolygon(
                n=6,
                radius=0.47,
                start_angle=PI / 6,
                stroke_color=GREEN,
                stroke_width=2.7,
                fill_color="#103226",
                fill_opacity=1,
            ).move_to(box.get_center() + UP * 0.30)
            net = neural_network(WHITE_SOFT, 0.72).move_to(hexagon)
            rails = VGroup(
                line(LEFT * 0.63, RIGHT * 0.56, GREEN, 2.3),
                line(LEFT * 0.47, RIGHT * 0.30, GREEN, 2.3),
                line(LEFT * 0.59, RIGHT * 0.06, GREEN, 2.3),
            ).arrange(DOWN, buff=0.10, aligned_edge=LEFT).move_to(box.get_center() + DOWN * 0.53)
            return VGroup(box, hexagon, net, rails).scale(scale)

        def avatar(scale=1.0):
            halo = Circle(radius=0.34, stroke_color=PURPLE, stroke_width=2, fill_color="#1E1E3A", fill_opacity=1)
            head = Circle(radius=0.115, stroke_width=0, fill_color="#F0B394", fill_opacity=1).move_to(halo.get_center() + UP * 0.07)
            hair = Arc(radius=0.13, start_angle=0, angle=PI, color="#2E1D26", stroke_width=4).move_to(head.get_center() + UP * 0.03)
            shoulders = Arc(radius=0.19, start_angle=0, angle=PI, color=PURPLE, stroke_width=5).move_to(halo.get_center() + DOWN * 0.19)
            return VGroup(halo, head, hair, shoulders).scale(scale)

        def abstract_image(color, variant=0, scale=1.0):
            area = RoundedRectangle(
                width=1.60,
                height=1.08,
                corner_radius=0.12,
                stroke_color=MUTED,
                stroke_width=1.5,
                fill_color="#0E1925",
                fill_opacity=1,
            )
            sky = Rectangle(width=1.52, height=0.46, stroke_width=0, fill_color=color, fill_opacity=0.16).move_to(area.get_top() + DOWN * 0.31)
            if variant % 3 == 0:
                subject = VGroup(
                    Circle(radius=0.18, stroke_color=color, stroke_width=2.2),
                    Circle(radius=0.075, stroke_width=0, fill_color=color, fill_opacity=0.80),
                ).move_to(area.get_center() + RIGHT * 0.23 + DOWN * 0.05)
                ground = line(LEFT * 0.53, RIGHT * 0.54, color, 2.2, 0.78).move_to(area.get_bottom() + UP * 0.20)
            elif variant % 3 == 1:
                subject = VGroup(
                    Polygon(LEFT * 0.42 + DOWN * 0.25, LEFT * 0.05 + UP * 0.28, RIGHT * 0.30 + DOWN * 0.25, color=color, stroke_width=0, fill_opacity=0.65),
                    Polygon(LEFT * 0.05 + DOWN * 0.25, RIGHT * 0.26 + UP * 0.18, RIGHT * 0.56 + DOWN * 0.25, color=PURPLE, stroke_width=0, fill_opacity=0.55),
                ).move_to(area.get_center() + DOWN * 0.06)
                ground = line(LEFT * 0.55, RIGHT * 0.55, WHITE_SOFT, 1.6, 0.35).move_to(area.get_bottom() + UP * 0.18)
            else:
                subject = VGroup(
                    RoundedRectangle(width=0.44, height=0.52, corner_radius=0.08, stroke_color=color, stroke_width=2, fill_color=color, fill_opacity=0.22),
                    Circle(radius=0.10, stroke_color=WHITE_SOFT, stroke_width=1.8),
                ).arrange(DOWN, buff=0.02).move_to(area)
                ground = line(LEFT * 0.50, RIGHT * 0.50, color, 2, 0.55).move_to(area.get_bottom() + UP * 0.16)
            return VGroup(area, sky, subject, ground).scale(scale)

        def search_tile(kind, accent, scale=1.0):
            tile = RoundedRectangle(
                width=1.72,
                height=1.18,
                corner_radius=0.13,
                stroke_color=MUTED,
                stroke_width=1.45,
                fill_color="#0D1722",
                fill_opacity=1,
            )
            if kind == "perfume":
                image_path = perfume_pd_path if accent == PURPLE else perfume_met_path
                art = photo_asset(image_path, 1.46, 0.92).move_to(tile.get_center() + DOWN * 0.02)
                shine = Circle(radius=0.055, stroke_width=0, fill_color=WHITE_SOFT, fill_opacity=0.72).move_to(tile.get_center() + LEFT * 0.42 + UP * 0.30)
                return Group(tile, art, shine).scale(scale)
            if kind == "crop":
                art = photo_asset(perfume_met_path, 1.46, 0.95).move_to(tile.get_center() + RIGHT * 0.28 + DOWN * 0.02)
                mask = Rectangle(width=0.69, height=0.98, stroke_width=0, fill_color="#0D1722", fill_opacity=1).move_to(tile.get_center() + LEFT * 0.48)
                crop_edge = line(UP * 0.39, DOWN * 0.39, WHITE_SOFT, 1.4, 0.35).move_to(tile.get_center() + LEFT * 0.12)
                return Group(tile, art, mask, crop_edge).scale(scale)
            if kind == "avatar":
                art = photo_asset(avatar_path, 0.96, 0.96).move_to(tile)
                return Group(tile, art).scale(scale)
            image_paths = [fashion_path, landscape_path, plants_path]
            art = photo_asset(image_paths[kind % 3], 1.46, 0.92).move_to(tile)
            return Group(tile, art).scale(scale)

        def browser_shell():
            shell = soft_panel(12.15, 6.60, BLUE, 0.30, 0.30)
            top = RoundedRectangle(
                width=11.98,
                height=0.82,
                corner_radius=0.22,
                stroke_width=0,
                fill_color="#0E1824",
                fill_opacity=1,
            ).move_to(shell.get_top() + DOWN * 0.41)
            divider = line(shell.get_left() + RIGHT * 0.22 + UP * 2.89, shell.get_right() + LEFT * 0.22 + UP * 2.89, MUTED, 1.4, 0.85)
            lights = VGroup(
                Dot(radius=0.065, color=RED),
                Dot(radius=0.065, color=YELLOW),
                Dot(radius=0.065, color=GREEN),
            ).arrange(RIGHT, buff=0.12).move_to(shell.get_left() + RIGHT * 0.45 + UP * 2.89)
            google = SVGMobject(str(google_path)).set_height(0.42)
            google.set_fill(BLUE, opacity=1).set_stroke(width=0)
            google.move_to(shell.get_left() + RIGHT * 1.15 + UP * 2.88)
            bar = RoundedRectangle(
                width=5.45,
                height=0.47,
                corner_radius=0.24,
                stroke_color="#2E4056",
                stroke_width=1.6,
                fill_color="#111F2E",
                fill_opacity=1,
            ).move_to(shell.get_center() + UP * 2.88)
            lens = VGroup(
                Circle(radius=0.11, stroke_color=WHITE_SOFT, stroke_width=1.8),
                line(RIGHT * 0.06, RIGHT * 0.19 + DOWN * 0.13, WHITE_SOFT, 1.8),
            ).move_to(bar.get_left() + RIGHT * 0.30)
            mini_bottle = photo_asset(perfume_pd_path, 0.24, 0.28).move_to(bar.get_center() + RIGHT * 0.24)
            pulse_dot = Dot(radius=0.045, color=CYAN).move_to(bar.get_right() + LEFT * 0.25)
            return Group(shell, top, divider, lights, google, bar, lens, mini_bottle, pulse_dot)

        def error_mark(color=RED, scale=1.0):
            return VGroup(
                line(UL * 0.31, DR * 0.31, color, 4),
                line(UR * 0.31, DL * 0.31, color, 4),
            ).scale(scale)

        # ------------------------------------------------------------
        # Act 1: one clean, isolated training image.
        # ------------------------------------------------------------
        ambient = VGroup(
            Circle(radius=2.75, stroke_color=PURPLE, stroke_width=1.1, stroke_opacity=0.12),
            Circle(radius=3.70, stroke_color=CYAN, stroke_width=1.0, stroke_opacity=0.08),
        ).move_to(ORIGIN + LEFT * 0.20)
        self.add(ambient)

        photo = soft_panel(3.75, 5.15, PURPLE, 0.56, 0.28).move_to(LEFT * 3.20 + DOWN * 0.20)
        photo_inner = RoundedRectangle(
            width=3.15,
            height=4.43,
            corner_radius=0.20,
            stroke_color="#303F57",
            stroke_width=1.5,
            fill_color="#0E1926",
            fill_opacity=1,
        ).move_to(photo)
        bottle = photo_asset(perfume_pd_path, 2.12, 3.54).move_to(photo_inner.get_center() + DOWN * 0.18)
        bottle_target = target(CYAN, 2.18, 3.12).move_to(bottle)
        studio_sparkles = VGroup(
            Dot(radius=0.05, color=YELLOW),
            Dot(radius=0.035, color=WHITE_SOFT),
            Dot(radius=0.04, color=CYAN),
        ).arrange(DOWN, buff=0.35).move_to(photo_inner.get_right() + LEFT * 0.35 + UP * 1.22)
        model = model_card(1.30).move_to(RIGHT * 2.20 + UP * 0.35)
        train_arrow = Arrow(photo.get_right() + UP * 0.12, model.get_left() + DOWN * 0.05, buff=0.20, color=GREEN, stroke_width=4.3)
        train_group = Group(photo, photo_inner, bottle, bottle_target, studio_sparkles, model, train_arrow)

        self.play(FadeIn(photo, scale=0.90), FadeIn(photo_inner), FadeIn(bottle, shift=UP * 0.18), run_time=1.25)
        self.play(Create(bottle_target), FadeIn(studio_sparkles, scale=0.75), run_time=0.80)
        pulse(bottle_target, CYAN, 0.75)
        self.play(GrowArrow(train_arrow), FadeIn(model, scale=0.76), run_time=1.15)
        travelling_dot(train_arrow, GREEN, 0.90)
        pulse(model, GREEN, 0.80)
        self.wait(0.55)

        # ------------------------------------------------------------
        # Act 2: the real search page is crowded with small results.
        # ------------------------------------------------------------
        browser = browser_shell().move_to(ORIGIN + DOWN * 0.10)
        tile_specs = [
            ("perfume", PURPLE), (0, CYAN), ("avatar", PURPLE), (1, GREEN),
            (2, YELLOW), ("perfume", CYAN), ("crop", PURPLE), (0, GREEN),
            (1, CYAN), ("avatar", YELLOW), ("perfume", GREEN), ("crop", CYAN),
        ]
        tile_positions = [
            LEFT * 4.10 + UP * 1.25, LEFT * 1.38 + UP * 1.25, RIGHT * 1.38 + UP * 1.25, RIGHT * 4.10 + UP * 1.25,
            LEFT * 4.10 + DOWN * 0.30, LEFT * 1.38 + DOWN * 0.30, RIGHT * 1.38 + DOWN * 0.30, RIGHT * 4.10 + DOWN * 0.30,
            LEFT * 4.10 + DOWN * 1.84, LEFT * 1.38 + DOWN * 1.84, RIGHT * 1.38 + DOWN * 1.84, RIGHT * 4.10 + DOWN * 1.84,
        ]
        tiles = Group(*[
            search_tile(kind, accent).move_to(position + DOWN * 0.10)
            for (kind, accent), position in zip(tile_specs, tile_positions)
        ])

        self.play(FadeOut(train_group, shift=LEFT * 0.25), FadeIn(browser, shift=UP * 0.22), run_time=1.20)
        self.play(
            LaggedStart(*[FadeIn(tile, scale=0.68) for tile in tiles], lag_ratio=0.08),
            run_time=1.80,
        )
        selected_tile = tiles[0]
        spotlight = RoundedRectangle(
            width=1.92,
            height=1.38,
            corner_radius=0.17,
            stroke_color=YELLOW,
            stroke_width=2.8,
        ).move_to(selected_tile)
        self.play(Create(spotlight), run_time=0.55)
        self.play(spotlight.animate.move_to(tiles[5]), run_time=0.75, rate_func=rate_functions.ease_in_out_sine)
        self.play(FadeOut(spotlight), run_time=0.40)
        self.wait(0.65)

        # ------------------------------------------------------------
        # Act 3: the original model cannot read tiny or cropped images.
        # ------------------------------------------------------------
        model_badge = model_card(0.64).move_to(LEFT * 5.35 + DOWN * 2.38)
        scan_source = Dot(radius=0.08, color=GREEN).move_to(model_badge.get_top() + UP * 0.06)
        scan_line = line(LEFT * 5.35 + UP * 2.20, RIGHT * 5.30 + UP * 2.20, GREEN, 2.6, 0.82)
        scan_line.set_z_index(3)
        connector = Arrow(model_badge.get_top() + UP * 0.04, LEFT * 5.35 + UP * 1.98, buff=0.08, color=GREEN, stroke_width=3.2)
        tiny_target = target(RED, 1.20, 0.86).move_to(tiles[5])
        crop_target = target(RED, 1.20, 0.86).move_to(tiles[6])
        avatar_target = target(RED, 1.20, 0.86).move_to(tiles[9])
        failure_targets = VGroup(tiny_target, crop_target, avatar_target)
        marks = VGroup(
            error_mark().move_to(tiles[5].get_center()),
            error_mark().move_to(tiles[6].get_center()),
            error_mark().move_to(tiles[9].get_center()),
        )

        self.play(FadeIn(model_badge, shift=UP * 0.12), GrowArrow(connector), FadeIn(scan_source), run_time=0.95)
        self.add(scan_line)
        self.play(scan_line.animate.shift(DOWN * 4.10), run_time=1.35, rate_func=linear)
        self.remove(scan_line)
        self.play(Create(failure_targets), run_time=0.70)
        self.play(
            LaggedStart(*[FadeIn(mark, scale=0.55) for mark in marks], lag_ratio=0.18),
            run_time=0.85,
        )
        self.play(
            tiles[5].animate.shift(LEFT * 0.08),
            tiles[6].animate.shift(RIGHT * 0.08),
            tiles[9].animate.shift(LEFT * 0.08),
            run_time=0.22,
            rate_func=there_and_back,
        )
        model_error = error_mark(RED, 1.45).move_to(model_badge)
        self.play(FadeIn(model_error, scale=0.55), run_time=0.55)
        self.wait(1.80)

class MusicMuteFocusScene(Scene):
    """Wordless training-to-inference story for music separation."""

    def construct(self):
        self.camera.background_color = BLACK

        CYAN = "#49E4D0"
        BLUE = "#65A9FF"
        GREEN = "#62E49B"
        YELLOW = "#F6C95F"
        RED = "#FF6D73"
        PURPLE = "#AE91FF"
        WHITE_SOFT = "#EAF2FB"
        PANEL = "#0A141F"
        PANEL_2 = "#142232"
        MUTED = "#33485C"

        def line(start, end, color, width=4.4, opacity=1):
            return Line(start, end, color=color, stroke_width=max(width, 4.4), stroke_opacity=opacity)

        def box(center, width, height, color, opacity=0.30, fill=PANEL):
            return RoundedRectangle(
                width=width,
                height=height,
                corner_radius=0.22,
                stroke_color=color,
                stroke_width=5.0,
                fill_color=fill,
                fill_opacity=opacity,
            ).move_to(center)

        def wave(color, cycles, amplitude=0.25, width=2.0, phase=0.0, thickness=3.5):
            points = []
            for x in np.linspace(-width / 2, width / 2, 100):
                envelope = 0.78 + 0.22 * np.cos(x * 0.65)
                y = amplitude * envelope * np.sin(cycles * x + phase)
                points.append(np.array([x, y, 0]))
            path = VMobject()
            path.set_points_smoothly(points)
            path.set_stroke(color, width=max(thickness, 5.2), opacity=1.0)
            return path

        def music_note(color=YELLOW, scale=1.0):
            head = Ellipse(width=0.34, height=0.18, stroke_color=color, stroke_width=5.0, fill_color=color, fill_opacity=0.95)
            stem = line(RIGHT * 0.12 + DOWN * 0.02, RIGHT * 0.12 + UP * 0.65, color, 3.2)
            flag = Polygon(
                RIGHT * 0.12 + UP * 0.65,
                RIGHT * 0.46 + UP * 0.51,
                RIGHT * 0.46 + UP * 0.35,
                RIGHT * 0.12 + UP * 0.46,
                color=color,
                stroke_width=0,
                fill_opacity=0.9,
            )
            return VGroup(head, stem, flag).scale(scale)

        def voice_icon(color=CYAN, scale=1.0):
            capsule = RoundedRectangle(width=0.40, height=0.80, corner_radius=0.19, stroke_color=color, stroke_width=5.0, fill_color=PANEL_2, fill_opacity=1)

            stem = line(DOWN * 0.24, UP * 0.10, color, 2.8).move_to(DOWN * 0.50)
            base = line(LEFT * 0.22, RIGHT * 0.22, color, 2.8).move_to(DOWN * 0.62)
            return VGroup(capsule, stem, base).scale(scale)

        def audio_card(center, color, icon, waves, width=2.75, height=1.55):
            frame = box(center, width, height, color, 0.38)
            icon.move_to(center + LEFT * (width / 2 - 0.45))
            content = VGroup()
            for item in waves:
                content.add(item)
            content.move_to(center + RIGHT * 0.45)
            return VGroup(frame, icon, content)

        def arrow(start, end, color, width=4.8):
            return Arrow(start, end, buff=0.16, color=color, stroke_width=max(width, 5.2), max_tip_length_to_length_ratio=0.22)

        def model_card(center, color=GREEN, scale=1.0):
            frame = box(center, 2.70, 2.30, color, 0.62)
            positions = [
                [LEFT * 0.62 + UP * 0.28, LEFT * 0.62 + DOWN * 0.28],
                [UP * 0.44, ORIGIN, DOWN * 0.44],
                [RIGHT * 0.62 + UP * 0.28, RIGHT * 0.62 + DOWN * 0.28],
            ]
            nodes = VGroup(*[Dot(point=p, radius=0.065, color=WHITE_SOFT) for col in positions for p in col])
            links = VGroup()
            for a in nodes[:2]:
                for b in nodes[2:5]:
                    links.add(line(a.get_center(), b.get_center(), color, 1.5, 0.48))
            for a in nodes[2:5]:
                for b in nodes[5:]:
                    links.add(line(a.get_center(), b.get_center(), color, 1.5, 0.48))
            rails = VGroup(
                line(LEFT * 0.62, RIGHT * 0.54, color, 2.3),
                line(LEFT * 0.46, RIGHT * 0.26, color, 2.3),
                line(LEFT * 0.57, RIGHT * 0.02, color, 2.3),
            ).arrange(DOWN, buff=0.09, aligned_edge=LEFT).move_to(center + DOWN * 0.72)
            links.move_to(center)
            nodes.move_to(center)
            return VGroup(frame, links, nodes, rails).scale(scale)

        def error_bars(center, color=RED, count=10, scale=1.0):
            bars = VGroup()
            for i in range(count):
                height = 0.20 + 0.16 * (0.5 + 0.5 * np.sin(i * 1.8))
                bars.add(line(DOWN * height, UP * height, color, 2.7).move_to(LEFT * 0.95 + RIGHT * i * 0.21 + center))
            return bars.scale(scale)

        def check_mark(color=GREEN, scale=1.0):
            return VGroup(
                line(LEFT * 0.34 + DOWN * 0.02, LEFT * 0.08 + DOWN * 0.28, color, 4.5),
                line(LEFT * 0.08 + DOWN * 0.28, RIGHT * 0.42 + UP * 0.30, color, 4.5),
            ).scale(scale)

        def pulse(mob, color, run_time=0.65):
            ring = Circle(radius=max(mob.width, mob.height) * 0.42, stroke_color=color, stroke_width=3.2).move_to(mob)
            self.add(ring)
            self.play(ring.animate.scale(1.24).set_opacity(0), run_time=run_time, rate_func=rate_functions.ease_out_expo)
            self.remove(ring)

        # ------------------------------------------------------------
        # Scene 1: create a mixed training example from two clean sources.
        # ------------------------------------------------------------
        source_voice = audio_card(
            LEFT * 4.35 + UP * 1.12,
            CYAN,
            voice_icon(CYAN, 0.72),
            [wave(CYAN, 2.15, 0.20, 1.55, -0.18, 2.7)],
        )
        source_music = audio_card(
            LEFT * 4.35 + DOWN * 1.12,
            YELLOW,
            music_note(YELLOW, 0.55),
            [wave(YELLOW, 5.1, 0.20, 1.55, 0.12, 2.7)],
        )
        mixer = box(RIGHT * 1.25, 7.25, 4.55, PURPLE, 0.24)
        mixed_voice = wave(CYAN, 2.15, 0.43, 5.95, -0.18, 4.2).move_to(mixer.get_center() + DOWN * 0.25)
        mixed_music = wave(YELLOW, 5.1, 0.46, 5.95, 0.12, 4.2).move_to(mixer.get_center() + UP * 0.24)
        merge_ring = Circle(radius=0.78, stroke_color=RED, stroke_width=3.2).move_to(mixer.get_center())
        path_voice = arrow(source_voice.get_right(), mixer.get_left() + UP * 0.70, CYAN, 3.0)
        path_music = arrow(source_music.get_right(), mixer.get_left() + DOWN * 0.70, YELLOW, 3.0)
        scene1 = VGroup(source_voice, source_music, mixer, mixed_voice, mixed_music, merge_ring, path_voice, path_music)

        self.play(FadeIn(source_voice, shift=RIGHT * 0.16), FadeIn(source_music, shift=RIGHT * 0.16), run_time=0.95)
        self.play(GrowArrow(path_voice), GrowArrow(path_music), run_time=0.85)
        self.play(FadeIn(mixer, scale=0.88), Create(mixed_voice), Create(mixed_music), run_time=1.10)
        self.play(Create(merge_ring), run_time=0.55)
        pulse(mixed_voice, CYAN, 0.55)
        pulse(mixed_music, YELLOW, 0.55)
        self.wait(0.45)

        # ------------------------------------------------------------
        # Scene 2: supervised training — prediction, comparison, feedback.
        # ------------------------------------------------------------

        self.play(FadeOut(scene1), run_time=0.85)
        train_frame = box(DOWN * 0.05, 12.25, 7.25, BLUE, 0.12)
        train_input = audio_card(LEFT * 4.35 + UP * 1.45, PURPLE, voice_icon(WHITE_SOFT, 0.58), [
            wave(CYAN, 2.15, 0.19, 1.55, -0.18, 2.4),
            wave(YELLOW, 5.1, 0.17, 1.55, 0.12, 2.4),
        ])
        learner = model_card(ORIGIN + UP * 1.45, GREEN, 1.04)
        predicted_frame = box(RIGHT * 4.30 + UP * 1.45, 2.90, 2.70, RED, 0.22)
        predicted_voice = wave(CYAN, 3.35, 0.17, 1.72, 0.62, 3.0).move_to(predicted_frame.get_center() + UP * 0.42)
        predicted_music = wave(YELLOW, 3.0, 0.16, 1.72, 1.30, 3.0).move_to(predicted_frame.get_center() + DOWN * 0.46)
        pred_voice_icon = voice_icon(CYAN, 0.40).move_to(predicted_frame.get_left() + RIGHT * 0.28 + UP * 0.77)
        pred_music_icon = music_note(YELLOW, 0.32).move_to(predicted_frame.get_left() + RIGHT * 0.28 + DOWN * 0.49)

        target_frame = box(RIGHT * 2.00 + DOWN * 2.35, 4.35, 1.65, GREEN, 0.20)
        target_voice = wave(CYAN, 2.15, 0.19, 2.05, -0.18, 3.0).move_to(target_frame.get_center() + LEFT * 1.38)
        target_music = wave(YELLOW, 5.1, 0.19, 2.05, 0.12, 3.0).move_to(target_frame.get_center() + RIGHT * 1.38)
        truth_voice_icon = voice_icon(CYAN, 0.35).move_to(target_frame.get_left() + RIGHT * 0.28)
        truth_music_icon = music_note(YELLOW, 0.30).move_to(target_frame.get_right() + LEFT * 0.28)
        target_separator = line(target_frame.get_center() + UP * 0.48, target_frame.get_center() + DOWN * 0.48, GREEN, 3.0, 0.78)

        loss_frame = box(LEFT * 2.20 + DOWN * 2.35, 2.75, 1.65, RED, 0.18)
        loss_bars = error_bars(loss_frame.get_center() + RIGHT * 0.11, RED, 9, 1.00)
        feedback = Arrow(loss_frame.get_top() + UP * 0.08, learner.get_bottom() + DOWN * 0.08, color=RED, stroke_width=5.2, buff=0.16)
        input_path = arrow(train_input.get_right(), learner.get_left(), PURPLE, 3.5)
        prediction_path = arrow(learner.get_right(), predicted_frame.get_left(), GREEN, 3.5)
        compare_path = arrow(predicted_frame.get_bottom(), target_frame.get_top(), RED, 3.0)
        train_group = VGroup(
            train_frame,
            train_input,
            learner,
            predicted_frame,
            predicted_voice,
            predicted_music,
            pred_voice_icon,
            pred_music_icon,
            target_frame,
            target_voice,
            target_music,
            truth_voice_icon,
            truth_music_icon,
            target_separator,
            loss_frame,
            loss_bars,
            feedback,
            input_path,
            prediction_path,
            compare_path,
        )

        self.play(FadeIn(train_frame), FadeIn(train_input, scale=0.75), FadeIn(learner, scale=0.78), run_time=1.05)
        self.play(GrowArrow(input_path), run_time=0.75)
        self.play(FadeIn(predicted_frame), FadeIn(predicted_voice), FadeIn(predicted_music), FadeIn(pred_voice_icon), FadeIn(pred_music_icon), GrowArrow(prediction_path), run_time=1.05)
        self.play(FadeIn(target_frame), Create(target_voice), Create(target_music), FadeIn(truth_voice_icon), FadeIn(truth_music_icon), Create(target_separator), GrowArrow(compare_path), run_time=1.05)
        self.play(FadeIn(loss_frame), Create(loss_bars), GrowArrow(feedback), run_time=0.95)
        pulse(loss_bars, RED, 0.55)

        improved_voice = wave(CYAN, 2.15, 0.19, 1.72, -0.18, 3.0).move_to(predicted_voice)
        improved_music = wave(YELLOW, 5.1, 0.19, 1.72, 0.12, 3.0).move_to(predicted_music)
        self.play(
            Transform(predicted_voice, improved_voice),
            Transform(predicted_music, improved_music),
            loss_bars.animate.scale(0.62),
            run_time=1.35,
            rate_func=rate_functions.ease_in_out_sine,
        )
        pulse(learner, GREEN, 0.65)
        improved_voice_2 = wave(CYAN, 2.15, 0.19, 1.72, -0.18, 3.0).move_to(predicted_voice)
        improved_music_2 = wave(YELLOW, 5.1, 0.19, 1.72, 0.12, 3.0).move_to(predicted_music)
        self.play(
            Transform(predicted_voice, improved_voice_2),
            Transform(predicted_music, improved_music_2),
            loss_bars.animate.scale(0.36).set_opacity(0.48),
            run_time=1.20,
            rate_func=rate_functions.ease_in_out_sine,
        )
        self.play(FadeIn(check_mark(GREEN, 0.75).move_to(loss_frame)), FadeOut(feedback), run_time=0.65)
        self.wait(0.70)

        # ------------------------------------------------------------
        # Scene 3: inference on a new mixed signal, then mute music.
        # ------------------------------------------------------------
        self.play(FadeOut(train_group), run_time=0.90)
        input_frame = box(LEFT * 4.25, 3.05, 4.65, PURPLE, 0.24)
        input_icon = voice_icon(WHITE_SOFT, 0.72).move_to(input_frame.get_center() + UP * 1.42)
        new_voice = wave(CYAN, 2.15, 0.26, 2.05, 0.46, 3.4).move_to(input_frame.get_center() + DOWN * 0.05)
        new_music = wave(YELLOW, 5.1, 0.24, 2.05, -0.20, 3.4).move_to(input_frame.get_center() + DOWN * 0.77)
        inference_model = model_card(ORIGIN, GREEN, 1.22)
        output_frame = box(RIGHT * 4.25, 4.00, 4.65, GREEN, 0.22)
        clean_voice = wave(CYAN, 2.15, 0.27, 2.65, 0.46, 3.8).move_to(output_frame.get_center() + UP * 0.82)
        clean_music = wave(YELLOW, 5.1, 0.20, 2.65, -0.20, 3.2).move_to(output_frame.get_center() + DOWN * 0.67)
        clean_music.set_opacity(0.10)
        out_voice_icon = voice_icon(CYAN, 0.50).move_to(output_frame.get_left() + RIGHT * 0.40 + UP * 0.82)
        out_music_icon = music_note(YELLOW, 0.38).move_to(output_frame.get_left() + RIGHT * 0.40 + DOWN * 0.67)
        mute = VGroup(
            music_note(RED, 0.72),
            line(UL * 0.48, DR * 0.48, RED, 5.0),
        ).move_to(output_frame.get_right() + LEFT * 0.34 + DOWN * 0.65)
        inference_a = arrow(input_frame.get_right(), inference_model.get_left(), PURPLE, 3.8)
        inference_b = arrow(inference_model.get_right(), output_frame.get_left(), GREEN, 3.8)
        final_group = VGroup(input_frame, input_icon, new_voice, new_music, inference_model, output_frame, clean_voice, clean_music, out_voice_icon, out_music_icon, mute, inference_a, inference_b)

        self.play(FadeIn(input_frame), FadeIn(input_icon, scale=0.75), Create(new_voice), Create(new_music), GrowArrow(inference_a), run_time=1.05)
        self.play(FadeIn(inference_model, scale=0.78), GrowArrow(inference_b), run_time=1.00)
        self.play(FadeIn(output_frame), Create(clean_voice), Create(clean_music), FadeIn(out_voice_icon), FadeIn(out_music_icon), FadeIn(mute, scale=0.70), run_time=1.20)
        self.play(
            clean_music.animate.set_opacity(0.02),
            mute.animate.scale(1.15),
            run_time=0.75,
            rate_func=there_and_back,
        )
        pulse(clean_voice, CYAN, 0.70)
        self.wait(2.05)

if __name__ == "__main__":
    pass


class LegacyTrainedModelToCppEngineScene(Scene):
    """Wordless continuation: trained model -> C++ AI engine -> Kotlin app."""

    def construct(self):
        self.camera.background_color = BLACK

        CYAN = "#43E6D0"
        BLUE = "#60A5FA"
        GREEN = "#55D98B"
        YELLOW = "#F6C85F"
        RED = "#FF6B6B"
        PURPLE = "#A78BFA"
        ORANGE = "#F59E6B"

        PANEL = "#081018"
        PANEL_2 = "#101923"
        LINE = "#33475B"
        WHITE_SOFT = "#E5EEF8"

        cpp_path = BRAND / "cplusplus.svg"
        kotlin_path = BRAND / "kotlin.svg"
        android_path = BRAND / "android.svg"
        apple_path = BRAND / "apple.svg"

        def logo(path, color, height=0.52):
            mark = SVGMobject(str(path))
            mark.set_height(height)
            mark.set_fill(color, opacity=1)
            mark.set_stroke(color, width=0, opacity=0)
            return mark

        def phone(color, logo_path=None, logo_color=None, scale=1.0):
            outer = RoundedRectangle(
                width=1.58,
                height=2.86,
                corner_radius=0.22,
                stroke_color=color,
                stroke_width=3.0,
                fill_color=PANEL,
                fill_opacity=1,
            )
            screen = RoundedRectangle(
                width=1.27,
                height=2.36,
                corner_radius=0.12,
                stroke_color=LINE,
                stroke_width=1.4,
                fill_color=PANEL_2,
                fill_opacity=1,
            ).move_to(outer)
            notch = RoundedRectangle(
                width=0.42,
                height=0.08,
                corner_radius=0.04,
                stroke_width=0,
                fill_color=color,
                fill_opacity=0.72,
            ).move_to(outer.get_top() + DOWN * 0.18)
            home = Line(
                LEFT * 0.18,
                RIGHT * 0.18,
                color=LINE,
                stroke_width=2.1,
            ).move_to(outer.get_bottom() + UP * 0.17)
            group = VGroup(outer, screen, notch, home)
            if logo_path is not None:
                group.add(
                    logo(
                        logo_path,
                        logo_color or color,
                        height=0.47,
                    ).move_to(screen.get_center())
                )
            return group.scale(scale)

        def code_card(color, logo_path, scale=1.0):
            card = RoundedRectangle(
                width=1.70,
                height=1.32,
                corner_radius=0.18,
                stroke_color=color,
                stroke_width=2.8,
                fill_color=PANEL,
                fill_opacity=1,
            )
            mark = logo(logo_path, color, height=0.43).move_to(
                card.get_center() + UP * 0.23
            )
            syntax = VGroup(
                Line(LEFT * 0.48, RIGHT * 0.39, color=color, stroke_width=2.4),
                Line(LEFT * 0.34, RIGHT * 0.18, color=color, stroke_width=2.4),
                Line(LEFT * 0.45, RIGHT * 0.02, color=color, stroke_width=2.4),
            ).arrange(
                DOWN,
                buff=0.10,
                aligned_edge=LEFT,
            ).move_to(card.get_center() + DOWN * 0.30)
            return VGroup(card, mark, syntax).scale(scale)

        def blur_grid(width, height):
            cells = VGroup()
            colors = [BLUE, PURPLE, CYAN, GREEN, YELLOW, RED]
            cols, rows = 6, 4
            cell_w, cell_h = width / cols, height / rows
            for row in range(rows):
                for col in range(cols):
                    cell = Rectangle(
                        width=cell_w + 0.012,
                        height=cell_h + 0.012,
                        stroke_width=0,
                        fill_color=colors[(row + col * 2) % len(colors)],
                        fill_opacity=0.50,
                    )
                    cell.move_to(
                        LEFT * width / 2
                        + RIGHT * (cell_w / 2 + col * cell_w)
                        + UP * height / 2
                        + DOWN * (cell_h / 2 + row * cell_h)
                    )
                    cells.add(cell)
            return cells

        def neural_network(color):
            positions = [
                LEFT * 0.42 + UP * 0.35,
                LEFT * 0.42,
                LEFT * 0.42 + DOWN * 0.35,
                ORIGIN + UP * 0.48,
                ORIGIN + UP * 0.16,
                ORIGIN + DOWN * 0.16,
                ORIGIN + DOWN * 0.48,
                RIGHT * 0.42 + UP * 0.35,
                RIGHT * 0.42,
                RIGHT * 0.42 + DOWN * 0.35,
            ]
            nodes = VGroup(
                *[
                    Dot(point=point, radius=0.075, color=color)
                    for point in positions
                ]
            )
            links = VGroup()
            left_nodes = nodes[:3]
            middle_nodes = nodes[3:7]
            right_nodes = nodes[7:]
            for left_node in left_nodes:
                for middle_node in middle_nodes:
                    links.add(
                        Line(
                            left_node.get_center(),
                            middle_node.get_center(),
                            color=color,
                            stroke_width=1.3,
                            stroke_opacity=0.45,
                        )
                    )
            for middle_node in middle_nodes:
                for right_node in right_nodes:
                    links.add(
                        Line(
                            middle_node.get_center(),
                            right_node.get_center(),
                            color=color,
                            stroke_width=1.3,
                            stroke_opacity=0.45,
                        )
                    )
            return VGroup(links, nodes)

        def trained_model(color=YELLOW):
            frame = RoundedRectangle(
                width=2.20,
                height=1.70,
                corner_radius=0.20,
                stroke_color=color,
                stroke_width=2.8,
                fill_color=PANEL,
                fill_opacity=1,
            )
            network = neural_network(color).scale(0.95).move_to(frame)
            return VGroup(frame, network)

        def engine_box(color, scale=1.0):
            box = RoundedRectangle(
                width=2.30,
                height=1.82,
                corner_radius=0.22,
                stroke_color=color,
                stroke_width=3.2,
                fill_color=PANEL,
                fill_opacity=1,
            )
            mark = logo(cpp_path, color, height=0.75).move_to(
                box.get_center() + UP * 0.20
            )
            lines = VGroup(
                Line(LEFT * 0.62, RIGHT * 0.54, color=color, stroke_width=2.6),
                Line(LEFT * 0.45, RIGHT * 0.30, color=color, stroke_width=2.6),
                Line(LEFT * 0.58, RIGHT * 0.06, color=color, stroke_width=2.6),
            ).arrange(
                DOWN,
                buff=0.09,
                aligned_edge=LEFT,
            ).move_to(box.get_center() + DOWN * 0.38)
            return VGroup(box, mark, lines).scale(scale)

        def image_recognition_icon(color):
            frame = RoundedRectangle(
                width=1.55,
                height=1.08,
                corner_radius=0.12,
                stroke_color=color,
                stroke_width=2.8,
                fill_color=PANEL,
                fill_opacity=1,
            )
            corners = VGroup(
                Line(frame.get_corner(UL), frame.get_corner(UL) + RIGHT * 0.18, color=color, stroke_width=2.8),
                Line(frame.get_corner(UL), frame.get_corner(UL) + DOWN * 0.18, color=color, stroke_width=2.8),
                Line(frame.get_corner(DR), frame.get_corner(DR) + LEFT * 0.18, color=color, stroke_width=2.8),
                Line(frame.get_corner(DR), frame.get_corner(DR) + UP * 0.18, color=color, stroke_width=2.8),
            )
            target = VGroup(
                Circle(radius=0.20, stroke_color=color, stroke_width=2.2),
                Line(LEFT * 0.34, RIGHT * 0.34, color=color, stroke_width=1.8),
                Line(UP * 0.34, DOWN * 0.34, color=color, stroke_width=1.8),
            ).move_to(frame)
            return VGroup(frame, corners, target)

        def speed_gauge(color):
            arc = Arc(
                radius=0.56,
                start_angle=PI * 0.10,
                angle=PI * 0.80,
                color=color,
                stroke_width=4,
            )
            ticks = VGroup()
            for angle in np.linspace(PI * 0.10, PI * 0.90, 5):
                outer = np.array([np.cos(angle), np.sin(angle), 0]) * 0.58
                inner = np.array([np.cos(angle), np.sin(angle), 0]) * 0.45
                ticks.add(Line(inner, outer, color=color, stroke_width=2.2))
            needle = Line(
                ORIGIN,
                RIGHT * 0.42,
                color=WHITE_SOFT,
                stroke_width=3.0,
            ).rotate(PI * 0.28)
            hub = Dot(radius=0.07, color=color)
            return VGroup(arc, ticks, needle, hub)

        def cross_mark(color):
            return VGroup(
                Line(UL * 0.42, DR * 0.42, color=color, stroke_width=5),
                Line(UR * 0.42, DL * 0.42, color=color, stroke_width=5),
            )

        def pulse(mob, color, run_time=0.85):
            ring = Circle(
                radius=max(mob.width, mob.height) * 0.48,
                stroke_color=color,
                stroke_width=3.5,
            ).move_to(mob)
            self.add(ring)
            self.play(
                ring.animate.scale(1.22).set_opacity(0),
                run_time=run_time,
                rate_func=rate_functions.ease_out_expo,
            )
            self.remove(ring)

        # ------------------------------------------------------------
        # 1) A trained model becomes a runtime engine.
        # ------------------------------------------------------------
        model = trained_model(YELLOW).move_to(LEFT * 4.55 + UP * 1.55)
        model_glow = Circle(
            radius=1.10,
            stroke_color=YELLOW,
            stroke_width=3,
        ).move_to(model)

        engine = engine_box(BLUE).move_to(ORIGIN + UP * 1.55)
        model_to_engine = Arrow(
            model.get_right(),
            engine.get_left(),
            buff=0.22,
            color=YELLOW,
            stroke_width=4,
        )

        self.play(
            FadeIn(model, scale=0.78),
            run_time=1.0,
        )
        pulse(model, YELLOW, run_time=0.9)
        self.wait(0.45)
        self.play(
            GrowArrow(model_to_engine),
            FadeIn(engine, scale=0.80),
            run_time=1.25,
        )
        packet = Dot(radius=0.10, color=YELLOW).move_to(
            model_to_engine.get_start()
        )
        self.add(packet)
        self.play(
            MoveAlongPath(
                packet,
                Line(
                    model_to_engine.get_start(),
                    model_to_engine.get_end(),
                ),
            ),
            run_time=1.15,
            rate_func=linear,
        )
        self.remove(packet)
        pulse(engine, BLUE, run_time=0.95)
        self.wait(0.55)

        # ------------------------------------------------------------
        # 2) Image recognition and processing speed.
        # ------------------------------------------------------------
        recognition = image_recognition_icon(CYAN).move_to(
            LEFT * 2.55 + DOWN * 0.75
        )
        gauge = speed_gauge(GREEN).move_to(
            RIGHT * 2.55 + DOWN * 0.75
        )
        engine_to_recognition = Arrow(
            engine.get_bottom(),
            recognition.get_top(),
            buff=0.20,
            color=CYAN,
            stroke_width=3.5,
        )
        engine_to_gauge = Arrow(
            engine.get_bottom(),
            gauge.get_top(),
            buff=0.20,
            color=GREEN,
            stroke_width=3.5,
        )
        self.play(
            GrowArrow(engine_to_recognition),
            GrowArrow(engine_to_gauge),
            FadeIn(recognition, scale=0.72),
            FadeIn(gauge, scale=0.72),
            run_time=1.15,
        )
        self.play(
            ShowPassingFlash(
                engine_to_recognition.copy().set_stroke(CYAN, width=8),
                time_width=0.32,
            ),
            ShowPassingFlash(
                engine_to_gauge.copy().set_stroke(GREEN, width=8),
                time_width=0.32,
            ),
            run_time=0.95,
        )
        self.play(
            gauge[2].animate.rotate(-PI * 0.38),
            run_time=0.80,
            rate_func=rate_functions.ease_out_expo,
        )
        pulse(recognition, CYAN, run_time=0.70)
        pulse(gauge, GREEN, run_time=0.70)
        self.wait(0.65)

        # ------------------------------------------------------------
        # 3) Two clean layers: Kotlin app shell + C++ AI engine.
        # ------------------------------------------------------------
        self.play(
            FadeOut(model),
            FadeOut(model_glow),
            FadeOut(model_to_engine),
            FadeOut(recognition),
            FadeOut(gauge),
            FadeOut(engine_to_recognition),
            FadeOut(engine_to_gauge),
            engine.animate.scale(0.82).move_to(ORIGIN + UP * 0.60),
            run_time=1.05,
        )

        kotlin_layer = code_card(BLUE, kotlin_path, scale=1.12).move_to(
            LEFT * 3.20 + UP * 0.60
        )
        layer_arrow = Arrow(
            kotlin_layer.get_right(),
            engine.get_left(),
            buff=0.20,
            color=BLUE,
            stroke_width=4,
        )
        android_phone = phone(
            PURPLE,
            android_path,
            GREEN,
            scale=0.72,
        ).move_to(RIGHT * 2.65 + DOWN * 1.55)
        apple_phone = phone(
            PURPLE,
            apple_path,
            WHITE_SOFT,
            scale=0.72,
        ).move_to(RIGHT * 4.35 + DOWN * 1.55)
        engine_to_android = Arrow(
            engine.get_bottom() + LEFT * 0.25,
            android_phone.get_top(),
            buff=0.18,
            color=PURPLE,
            stroke_width=3.5,
        )
        engine_to_apple = Arrow(
            engine.get_bottom() + RIGHT * 0.25,
            apple_phone.get_top(),
            buff=0.18,
            color=PURPLE,
            stroke_width=3.5,
        )
        self.play(
            FadeIn(kotlin_layer, scale=0.75),
            GrowArrow(layer_arrow),
            GrowArrow(engine_to_android),
            GrowArrow(engine_to_apple),
            FadeIn(android_phone, shift=UP * 0.15),
            FadeIn(apple_phone, shift=UP * 0.15),
            run_time=1.45,
        )
        self.play(
            ShowPassingFlash(
                layer_arrow.copy().set_stroke(BLUE, width=8),
                time_width=0.30,
            ),
            ShowPassingFlash(
                engine_to_android.copy().set_stroke(PURPLE, width=8),
                time_width=0.30,
            ),
            ShowPassingFlash(
                engine_to_apple.copy().set_stroke(PURPLE, width=8),
                time_width=0.30,
            ),
            run_time=1.15,
        )
        pulse(engine, BLUE, run_time=0.80)
        self.wait(0.75)

        # ------------------------------------------------------------
        # 4) Visual contrast: duplicated Kotlin work versus one shared core.
        # ------------------------------------------------------------
        architecture = VGroup(
            kotlin_layer,
            layer_arrow,
            engine,
            engine_to_android,
            engine_to_apple,
            android_phone,
            apple_phone,
        )
        self.play(
            FadeOut(architecture),
            run_time=0.95,
        )

        kotlin_source = code_card(BLUE, kotlin_path, scale=1.05).move_to(
            ORIGIN + UP * 2.10
        )
        duplicate_android = code_card(RED, kotlin_path, scale=0.88).move_to(
            LEFT * 2.25 + DOWN * 0.10
        )
        duplicate_apple = code_card(RED, kotlin_path, scale=0.88).move_to(
            RIGHT * 2.25 + DOWN * 0.10
        )
        duplicate_android_phone = phone(
            RED,
            android_path,
            GREEN,
            scale=0.56,
        ).move_to(LEFT * 2.25 + DOWN * 1.85)
        duplicate_apple_phone = phone(
            RED,
            apple_path,
            WHITE_SOFT,
            scale=0.56,
        ).move_to(RIGHT * 2.25 + DOWN * 1.85)
        duplicate_a = Arrow(
            kotlin_source.get_bottom(),
            duplicate_android.get_top(),
            buff=0.12,
            color=RED,
            stroke_width=3.2,
        )
        duplicate_b = Arrow(
            kotlin_source.get_bottom(),
            duplicate_apple.get_top(),
            buff=0.12,
            color=RED,
            stroke_width=3.2,
        )
        duplicate_phone_a = Arrow(
            duplicate_android.get_bottom(),
            duplicate_android_phone.get_top(),
            buff=0.10,
            color=RED,
            stroke_width=2.8,
        )
        duplicate_phone_b = Arrow(
            duplicate_apple.get_bottom(),
            duplicate_apple_phone.get_top(),
            buff=0.10,
            color=RED,
            stroke_width=2.8,
        )
        duplicate_group = VGroup(
            kotlin_source,
            duplicate_android,
            duplicate_apple,
            duplicate_android_phone,
            duplicate_apple_phone,
            duplicate_a,
            duplicate_b,
            duplicate_phone_a,
            duplicate_phone_b,
        )
        self.play(
            FadeIn(kotlin_source, scale=0.75),
            GrowArrow(duplicate_a),
            GrowArrow(duplicate_b),
            FadeIn(duplicate_android, scale=0.72),
            FadeIn(duplicate_apple, scale=0.72),
            GrowArrow(duplicate_phone_a),
            GrowArrow(duplicate_phone_b),
            FadeIn(duplicate_android_phone, shift=UP * 0.12),
            FadeIn(duplicate_apple_phone, shift=UP * 0.12),
            run_time=1.45,
        )
        duplicate_cross = cross_mark(RED).scale(1.55).move_to(
            duplicate_group.get_center() + DOWN * 0.10
        )
        self.play(
            Create(duplicate_cross),
            run_time=0.75,
        )
        self.wait(0.70)

        # ------------------------------------------------------------
        # 5) Final architecture: one C++ core reused by both platforms.
        # ------------------------------------------------------------
        self.play(
            FadeOut(duplicate_group),
            FadeOut(duplicate_cross),
            run_time=0.95,
        )

        final_kotlin = code_card(BLUE, kotlin_path, scale=0.98).move_to(
            LEFT * 3.25 + UP * 0.75
        )
        final_engine = engine_box(GREEN, scale=0.92).move_to(
            ORIGIN + UP * 0.75
        )
        final_android = phone(
            GREEN,
            android_path,
            GREEN,
            scale=0.74,
        ).move_to(RIGHT * 2.55 + DOWN * 1.35)
        final_apple = phone(
            CYAN,
            apple_path,
            WHITE_SOFT,
            scale=0.74,
        ).move_to(RIGHT * 4.25 + DOWN * 1.35)
        final_a = Arrow(
            final_kotlin.get_right(),
            final_engine.get_left(),
            buff=0.18,
            color=BLUE,
            stroke_width=3.8,
        )
        final_b = Arrow(
            final_engine.get_bottom() + LEFT * 0.24,
            final_android.get_top(),
            buff=0.18,
            color=GREEN,
            stroke_width=3.4,
        )
        final_c = Arrow(
            final_engine.get_bottom() + RIGHT * 0.24,
            final_apple.get_top(),
            buff=0.18,
            color=CYAN,
            stroke_width=3.4,
        )
        final_group = VGroup(
            final_kotlin,
            final_engine,
            final_android,
            final_apple,
            final_a,
            final_b,
            final_c,
        )
        self.play(
            FadeIn(final_kotlin, scale=0.78),
            FadeIn(final_engine, scale=0.78),
            GrowArrow(final_a),
            GrowArrow(final_b),
            GrowArrow(final_c),
            FadeIn(final_android, shift=UP * 0.15),
            FadeIn(final_apple, shift=UP * 0.15),
            run_time=1.55,
        )
        self.play(
            ShowPassingFlash(
                final_a.copy().set_stroke(BLUE, width=8),
                time_width=0.28,
            ),
            ShowPassingFlash(
                final_b.copy().set_stroke(GREEN, width=8),
                time_width=0.28,
            ),
            ShowPassingFlash(
                final_c.copy().set_stroke(CYAN, width=8),
                time_width=0.28,
            ),
            run_time=1.10,
        )
        pulse(final_engine, GREEN, run_time=0.95)
        self.play(
            final_engine.animate.scale(1.06),
            final_android.animate.scale(1.05),
            final_apple.animate.scale(1.05),
            run_time=0.65,
            rate_func=there_and_back,
        )
        self.wait(2.25)


if __name__ == "__main__":
    pass


class TrainedModelToCppEngineScene(Scene):
    """Polished wordless continuation for the C++ AI-engine narration."""

    def construct(self):
        self.camera.background_color = BLACK

        CYAN = "#43E6D0"
        BLUE = "#60A5FA"
        GREEN = "#55D98B"
        YELLOW = "#F6C85F"
        RED = "#FF6B6B"
        PURPLE = "#A78BFA"
        ORANGE = "#F59E6B"

        PANEL = "#081018"
        PANEL_2 = "#101923"
        LINE = "#33475B"
        WHITE_SOFT = "#E5EEF8"

        cpp_path = BRAND / "cplusplus.svg"
        kotlin_path = BRAND / "kotlin.svg"
        android_path = BRAND / "android.svg"
        apple_path = BRAND / "apple.svg"

        def brand(path, color, height=0.5):
            mark = SVGMobject(str(path))
            mark.set_height(height)
            mark.set_fill(color, opacity=1)
            mark.set_stroke(color, width=0, opacity=0)
            return mark

        def panel(center, color, width=4.25, height=5.25):
            return RoundedRectangle(
                width=width,
                height=height,
                corner_radius=0.25,
                stroke_color=color,
                stroke_width=2.2,
                fill_color=PANEL,
                fill_opacity=0.20,
            ).move_to(center)

        def phone(color, logo_path, logo_color=None, scale=1.0):
            outer = RoundedRectangle(
                width=1.45,
                height=2.62,
                corner_radius=0.21,
                stroke_color=color,
                stroke_width=3.0,
                fill_color=PANEL,
                fill_opacity=1,
            )
            screen = RoundedRectangle(
                width=1.16,
                height=2.15,
                corner_radius=0.12,
                stroke_color=LINE,
                stroke_width=1.3,
                fill_color=PANEL_2,
                fill_opacity=1,
            ).move_to(outer)
            notch = RoundedRectangle(
                width=0.38,
                height=0.07,
                corner_radius=0.04,
                stroke_width=0,
                fill_color=color,
                fill_opacity=0.72,
            ).move_to(outer.get_top() + DOWN * 0.17)
            home = Line(
                LEFT * 0.16,
                RIGHT * 0.16,
                color=LINE,
                stroke_width=2,
            ).move_to(outer.get_bottom() + UP * 0.15)
            mark = brand(
                logo_path,
                logo_color or color,
                height=0.42,
            ).move_to(screen.get_center())
            return VGroup(outer, screen, notch, home, mark).scale(scale)

        def card(color, path, scale=1.0):
            box = RoundedRectangle(
                width=1.62,
                height=1.30,
                corner_radius=0.18,
                stroke_color=color,
                stroke_width=2.8,
                fill_color=PANEL,
                fill_opacity=1,
            )
            mark = brand(path, color, height=0.42).move_to(
                box.get_center() + UP * 0.22
            )
            lines = VGroup(
                Line(LEFT * 0.46, RIGHT * 0.38, color=color, stroke_width=2.3),
                Line(LEFT * 0.32, RIGHT * 0.18, color=color, stroke_width=2.3),
                Line(LEFT * 0.43, RIGHT * 0.02, color=color, stroke_width=2.3),
            ).arrange(DOWN, buff=0.09, aligned_edge=LEFT).move_to(
                box.get_center() + DOWN * 0.29
            )
            return VGroup(box, mark, lines).scale(scale)

        def network(color):
            columns = [
                [LEFT * 0.48 + UP * 0.36, LEFT * 0.48, LEFT * 0.48 + DOWN * 0.36],
                [ORIGIN + UP * 0.50, ORIGIN + UP * 0.17, ORIGIN + DOWN * 0.17, ORIGIN + DOWN * 0.50],
                [RIGHT * 0.48 + UP * 0.36, RIGHT * 0.48, RIGHT * 0.48 + DOWN * 0.36],
            ]
            dots = VGroup()
            for column in columns:
                for point in column:
                    dots.add(Dot(point=point, radius=0.07, color=color))
            links = VGroup()
            for left_node in dots[:3]:
                for middle_node in dots[3:7]:
                    links.add(Line(left_node.get_center(), middle_node.get_center(), color=color, stroke_width=1.2, stroke_opacity=0.42))
            for middle_node in dots[3:7]:
                for right_node in dots[7:]:
                    links.add(Line(middle_node.get_center(), right_node.get_center(), color=color, stroke_width=1.2, stroke_opacity=0.42))
            return VGroup(links, dots)

        def model_card():
            box = RoundedRectangle(
                width=2.22,
                height=1.72,
                corner_radius=0.22,
                stroke_color=YELLOW,
                stroke_width=2.8,
                fill_color=PANEL,
                fill_opacity=1,
            )
            return VGroup(box, network(YELLOW).scale(0.90).move_to(box))

        def engine(color, scale=1.0):
            box = RoundedRectangle(
                width=2.30,
                height=1.86,
                corner_radius=0.22,
                stroke_color=color,
                stroke_width=3.4,
                fill_color=PANEL,
                fill_opacity=1,
            )
            mark = brand(cpp_path, color, height=0.78).move_to(
                box.get_center() + UP * 0.21
            )
            bars = VGroup(
                Line(LEFT * 0.62, RIGHT * 0.54, color=color, stroke_width=2.5),
                Line(LEFT * 0.45, RIGHT * 0.30, color=color, stroke_width=2.5),
                Line(LEFT * 0.58, RIGHT * 0.05, color=color, stroke_width=2.5),
            ).arrange(DOWN, buff=0.09, aligned_edge=LEFT).move_to(
                box.get_center() + DOWN * 0.39
            )
            return VGroup(box, mark, bars).scale(scale)

        def image_probe(color):
            frame = RoundedRectangle(
                width=1.48,
                height=1.03,
                corner_radius=0.11,
                stroke_color=color,
                stroke_width=2.8,
                fill_color=PANEL,
                fill_opacity=1,
            )
            target = VGroup(
                Circle(radius=0.19, stroke_color=color, stroke_width=2.2),
                Line(LEFT * 0.34, RIGHT * 0.34, color=color, stroke_width=1.8),
                Line(UP * 0.34, DOWN * 0.34, color=color, stroke_width=1.8),
            ).move_to(frame)
            return VGroup(frame, target)

        def gauge(color):
            arc = Arc(
                radius=0.55,
                start_angle=PI * 0.10,
                angle=PI * 0.80,
                color=color,
                stroke_width=4,
            )
            ticks = VGroup()
            for angle in np.linspace(PI * 0.10, PI * 0.90, 5):
                a = np.array([np.cos(angle), np.sin(angle), 0])
                ticks.add(Line(a * 0.43, a * 0.57, color=color, stroke_width=2))
            needle = Line(ORIGIN, RIGHT * 0.42, color=WHITE_SOFT, stroke_width=3).rotate(PI * 0.24)
            return VGroup(arc, ticks, needle, Dot(radius=0.07, color=color))

        def blur_grid(width=0.90, height=0.62):
            cells = VGroup()
            colors = [BLUE, PURPLE, CYAN, GREEN, YELLOW, RED]
            cols, rows = 5, 4
            cw, ch = width / cols, height / rows
            for row in range(rows):
                for col in range(cols):
                    cell = Rectangle(
                        width=cw + 0.01,
                        height=ch + 0.01,
                        stroke_width=0,
                        fill_color=colors[(row + col) % len(colors)],
                        fill_opacity=0.58,
                    )
                    cell.move_to(
                        LEFT * width / 2 + RIGHT * (cw / 2 + col * cw)
                        + UP * height / 2 + DOWN * (ch / 2 + row * ch)
                    )
                    cells.add(cell)
            return cells

        def flow(start, end, color, width=4):
            return Arrow(start, end, buff=0.18, color=color, stroke_width=width)

        def pulse(mob, color, run_time=0.8):
            ring = Circle(
                radius=max(mob.width, mob.height) * 0.48,
                stroke_color=color,
                stroke_width=3.5,
            ).move_to(mob)
            self.add(ring)
            self.play(
                ring.animate.scale(1.22).set_opacity(0),
                run_time=run_time,
                rate_func=rate_functions.ease_out_expo,
            )
            self.remove(ring)

        def moving_dot(path, color, run_time=0.9):
            dot = Dot(radius=0.09, color=color).move_to(path.get_start())
            self.add(dot)
            self.play(
                MoveAlongPath(dot, Line(path.get_start(), path.get_end())),
                run_time=run_time,
                rate_func=linear,
            )
            self.remove(dot)

        def cross(color):
            return VGroup(
                Line(UL * 0.42, DR * 0.42, color=color, stroke_width=5),
                Line(UR * 0.42, DL * 0.42, color=color, stroke_width=5),
            )

        # ------------------------------------------------------------
        # Act 1: the trained model is loaded into a real engine.
        # ------------------------------------------------------------
        model = model_card().move_to(LEFT * 4.05 + UP * 1.34)
        model_panel = panel(LEFT * 4.05 + UP * 1.34, YELLOW, 3.05, 2.72)
        model.move_to(model_panel)
        core = engine(BLUE, 1.08).move_to(ORIGIN + UP * 1.34)
        core_panel = panel(ORIGIN + UP * 1.34, BLUE, 3.15, 2.72)
        load_path = flow(model.get_right(), core.get_left(), YELLOW, 4.2)
        model_to_core = VGroup(model_panel, model, core_panel, core, load_path)

        self.play(
            FadeIn(model_panel),
            FadeIn(model, scale=0.78),
            run_time=1.0,
        )
        pulse(model, YELLOW, run_time=0.85)
        self.play(
            GrowArrow(load_path),
            FadeIn(core_panel),
            FadeIn(core, scale=0.78),
            run_time=1.25,
        )
        moving_dot(load_path, YELLOW, run_time=1.05)
        pulse(core, BLUE, run_time=0.90)
        self.wait(0.45)

        # Image recognition and processing speed appear as capabilities.
        recognition = image_probe(CYAN).scale(1.22).move_to(LEFT * 2.00 + DOWN * 1.05)
        speed = gauge(GREEN).scale(1.22).move_to(RIGHT * 2.00 + DOWN * 1.05)
        rec_path = flow(core.get_bottom() + LEFT * 0.28, recognition.get_top(), CYAN, 3.4)
        speed_path = flow(core.get_bottom() + RIGHT * 0.28, speed.get_top(), GREEN, 3.4)
        self.play(
            GrowArrow(rec_path),
            GrowArrow(speed_path),
            FadeIn(recognition, scale=0.70),
            FadeIn(speed, scale=0.70),
            run_time=1.20,
        )
        moving_dot(rec_path, CYAN, run_time=0.75)
        moving_dot(speed_path, GREEN, run_time=0.75)
        self.play(
            speed[2].animate.rotate(-PI * 0.38),
            run_time=0.70,
            rate_func=rate_functions.ease_out_expo,
        )
        pulse(recognition, CYAN, run_time=0.65)
        pulse(speed, GREEN, run_time=0.65)
        self.wait(0.65)

        # ------------------------------------------------------------
        # Act 2: two layers, one shared AI core.
        # ------------------------------------------------------------
        self.play(
            FadeOut(model_to_core),
            FadeOut(recognition),
            FadeOut(speed),
            FadeOut(rec_path),
            FadeOut(speed_path),
            run_time=0.95,
        )

        app_panel = panel(LEFT * 3.55 + DOWN * 0.10, PURPLE, 3.30, 4.12)
        engine_panel = panel(ORIGIN + DOWN * 0.10, GREEN, 3.30, 4.12)
        output_panel = panel(RIGHT * 3.70 + DOWN * 0.10, CYAN, 3.95, 4.12)
        kotlin = card(PURPLE, kotlin_path, 1.22).move_to(
            LEFT * 3.55 + UP * 0.80
        )
        cpp = engine(GREEN, 1.06).move_to(
            ORIGIN + UP * 0.80
        )
        app_to_cpp = flow(kotlin.get_right(), cpp.get_left(), PURPLE, 3.8)
        android = phone(GREEN, android_path, GREEN, 0.88).move_to(
            RIGHT * 3.12 + DOWN * 0.78
        )
        ios = phone(CYAN, apple_path, WHITE_SOFT, 0.88).move_to(
            RIGHT * 4.47 + DOWN * 0.78
        )
        cpp_to_android = flow(cpp.get_right() + DOWN * 0.10, android.get_top(), GREEN, 3.4)
        cpp_to_ios = flow(cpp.get_right() + UP * 0.10, ios.get_top(), CYAN, 3.4)
        input_chip = VGroup(
            image_probe(CYAN).scale(0.62),
            gauge(GREEN).scale(0.56),
        ).arrange(RIGHT, buff=0.36).move_to(
            ORIGIN + DOWN * 1.20
        )

        architecture = VGroup(
            app_panel,
            engine_panel,
            output_panel,
            kotlin,
            cpp,
            app_to_cpp,
            android,
            ios,
            cpp_to_android,
            cpp_to_ios,
            input_chip,
        )
        self.play(
            FadeIn(app_panel),
            FadeIn(engine_panel),
            FadeIn(output_panel),
            FadeIn(kotlin, scale=0.75),
            FadeIn(cpp, scale=0.75),
            GrowArrow(app_to_cpp),
            GrowArrow(cpp_to_android),
            GrowArrow(cpp_to_ios),
            FadeIn(android, shift=UP * 0.14),
            FadeIn(ios, shift=UP * 0.14),
            FadeIn(input_chip, scale=0.65),
            run_time=1.70,
        )
        moving_dot(app_to_cpp, PURPLE, run_time=0.75)
        moving_dot(cpp_to_android, GREEN, run_time=0.75)
        moving_dot(cpp_to_ios, CYAN, run_time=0.75)
        pulse(cpp, GREEN, run_time=0.85)
        self.wait(0.90)

        # ------------------------------------------------------------
        # Act 3: make the rewrite cost visible, then replace it.
        # ------------------------------------------------------------
        self.play(
            FadeOut(architecture),
            run_time=0.90,
        )

        source = card(PURPLE, kotlin_path, 1.22).move_to(ORIGIN + UP * 2.25)
        left_copy = card(RED, kotlin_path, 1.03).move_to(LEFT * 2.25 + DOWN * 0.08)
        right_copy = card(RED, kotlin_path, 1.03).move_to(RIGHT * 2.25 + DOWN * 0.08)
        left_phone = phone(RED, android_path, GREEN, 0.64).move_to(
            LEFT * 2.25 + DOWN * 1.72
        )
        right_phone = phone(RED, apple_path, WHITE_SOFT, 0.64).move_to(
            RIGHT * 2.25 + DOWN * 1.72
        )
        split_a = flow(source.get_bottom(), left_copy.get_top(), RED, 3.2)
        split_b = flow(source.get_bottom(), right_copy.get_top(), RED, 3.2)
        copy_a = flow(left_copy.get_bottom(), left_phone.get_top(), RED, 2.8)
        copy_b = flow(right_copy.get_bottom(), right_phone.get_top(), RED, 2.8)
        rewrite = VGroup(
            source,
            left_copy,
            right_copy,
            left_phone,
            right_phone,
            split_a,
            split_b,
            copy_a,
            copy_b,
        )
        self.play(
            FadeIn(source, scale=0.75),
            GrowArrow(split_a),
            GrowArrow(split_b),
            FadeIn(left_copy, scale=0.72),
            FadeIn(right_copy, scale=0.72),
            GrowArrow(copy_a),
            GrowArrow(copy_b),
            FadeIn(left_phone, shift=UP * 0.12),
            FadeIn(right_phone, shift=UP * 0.12),
            run_time=1.55,
        )
        rewrite_cross = cross(RED).scale(1.55).move_to(
            VGroup(left_copy, right_copy).get_center()
        )
        self.play(
            Create(rewrite_cross),
            run_time=0.75,
        )
        self.wait(0.70)

        self.play(
            FadeOut(rewrite),
            FadeOut(rewrite_cross),
            run_time=0.85,
        )

        # ------------------------------------------------------------
        # Finale: one engine, two platforms, and visible blur output.
        # ------------------------------------------------------------
        final_app = panel(LEFT * 3.55 + DOWN * 0.10, PURPLE, 3.05, 4.12)
        final_core_panel = panel(ORIGIN + DOWN * 0.10, GREEN, 3.25, 4.12)
        final_output = panel(RIGHT * 3.70 + DOWN * 0.10, CYAN, 4.25, 4.12)
        final_kotlin = card(PURPLE, kotlin_path, 1.20).move_to(
            LEFT * 3.55 + UP * 0.80
        )
        final_cpp = engine(GREEN, 1.07).move_to(ORIGIN + UP * 0.80)
        final_android = phone(GREEN, android_path, GREEN, 0.82).move_to(
            RIGHT * 3.06 + DOWN * 0.76
        )
        final_ios = phone(CYAN, apple_path, WHITE_SOFT, 0.82).move_to(
            RIGHT * 4.47 + DOWN * 0.76
        )
        final_a = flow(final_kotlin.get_right(), final_cpp.get_left(), PURPLE, 3.5)
        final_b = flow(final_cpp.get_right() + DOWN * 0.12, final_android.get_top(), GREEN, 3.2)
        final_c = flow(final_cpp.get_right() + UP * 0.12, final_ios.get_top(), CYAN, 3.2)
        final_grid_a = blur_grid(0.72, 0.52).move_to(final_android[1].get_center())
        final_grid_b = blur_grid(0.72, 0.52).move_to(final_ios[1].get_center())
        final_group = VGroup(
            final_app,
            final_core_panel,
            final_output,
            final_kotlin,
            final_cpp,
            final_android,
            final_ios,
            final_a,
            final_b,
            final_c,
            final_grid_a,
            final_grid_b,
        )
        self.play(
            FadeIn(final_app),
            FadeIn(final_core_panel),
            FadeIn(final_output),
            FadeIn(final_kotlin, scale=0.75),
            FadeIn(final_cpp, scale=0.75),
            GrowArrow(final_a),
            GrowArrow(final_b),
            GrowArrow(final_c),
            FadeIn(final_android, shift=UP * 0.14),
            FadeIn(final_ios, shift=UP * 0.14),
            FadeIn(final_grid_a, scale=0.30),
            FadeIn(final_grid_b, scale=0.30),
            run_time=1.70,
        )
        moving_dot(final_a, PURPLE, run_time=0.70)
        moving_dot(final_b, GREEN, run_time=0.70)
        moving_dot(final_c, CYAN, run_time=0.70)
        pulse(final_cpp, GREEN, run_time=0.95)
        self.play(
            final_cpp.animate.scale(1.07),
            final_android.animate.scale(1.05),
            final_ios.animate.scale(1.05),
            run_time=0.70,
            rate_func=there_and_back,
        )
        self.wait(2.35)




class HaramBlurQuestionScene(Scene):
    """Wordless scene: an existing HaramBlur app triggers Low Byte's question."""

    def construct(self):
        self.camera.background_color = BLACK

        CYAN = "#49E4D0"
        BLUE = "#65A9FF"
        GREEN = "#62E49B"
        YELLOW = "#F6C95F"
        PURPLE = "#AE91FF"
        RED = "#FF6D73"
        WHITE_SOFT = "#EAF2FB"
        PANEL = "#09131E"
        PANEL_2 = "#122131"
        MUTED = "#456078"

        def panel(center, width, height, color, opacity=0.26, fill=PANEL):
            return RoundedRectangle(
                width=width, height=height, corner_radius=0.26,
                stroke_color=color, stroke_width=5.0,
                fill_color=fill, fill_opacity=opacity,
            ).move_to(center)

        def thick_line(start, end, color, width=5.0, opacity=1.0):
            return Line(start, end, color=color, stroke_width=max(width, 5.0), stroke_opacity=opacity)

        def pulse(mob, color, run_time=0.65):
            ring = Circle(
                radius=max(mob.width, mob.height) * 0.56,
                stroke_color=color, stroke_width=5.0, fill_opacity=0,
            ).move_to(mob)
            self.add(ring)
            self.play(
                ring.animate.scale(1.18).set_opacity(0),
                run_time=run_time,
                rate_func=rate_functions.ease_out_expo,
            )
            self.remove(ring)

        def question_mark(center, scale=1.0, color=YELLOW):
            curve = Arc(
                radius=0.32, start_angle=0.12 * PI, angle=1.32 * PI,
                color=color, stroke_width=7.0,
            )
            curve.stretch(0.82, dim=1)
            curve.move_to(center + UP * 0.10)
            stem = thick_line(center + UP * 0.02, center + DOWN * 0.22, color, 7.0)
            dot = Dot(center + DOWN * 0.46, radius=0.075, color=color)
            return VGroup(curve, stem, dot).scale(scale)

        def thinking_mouth(center):
            mouth = VMobject(color=BLACK, stroke_width=7.0)
            mouth.set_points_smoothly([
                center + LEFT * 0.14,
                center + LEFT * 0.03 + UP * 0.04,
                center + RIGHT * 0.14,
            ])
            return mouth

        logo_path = find_file("haramblur_logo.webp", ("assets", ""))
        logo = ImageMobject(str(logo_path)).set_height(1.28)

        # Existing app: real HaramBlur branding plus a protected feed.
        app_frame = panel(LEFT * 3.35 + DOWN * 0.10, 4.35, 5.75, PURPLE, 0.22)
        screen = panel(LEFT * 3.35 + DOWN * 0.10, 3.88, 5.24, CYAN, 0.18, PANEL_2)
        browser_bar = thick_line(
            screen.get_left() + RIGHT * 0.28 + UP * 1.95,
            screen.get_right() + LEFT * 0.28 + UP * 1.95,
            MUTED, 3.8, 0.9,
        )
        browser_dots = VGroup(
            Dot(screen.get_left() + RIGHT * 0.38 + UP * 2.22, radius=0.07, color=RED),
            Dot(screen.get_left() + RIGHT * 0.62 + UP * 2.22, radius=0.07, color=YELLOW),
            Dot(screen.get_left() + RIGHT * 0.86 + UP * 2.22, radius=0.07, color=GREEN),
        )
        logo_tile = RoundedRectangle(
            width=1.68, height=1.68, corner_radius=0.26,
            stroke_color=WHITE_SOFT, stroke_width=4.0,
            fill_color=WHITE_SOFT, fill_opacity=1,
        ).move_to(screen.get_center() + UP * 1.00)
        logo.move_to(logo_tile)
        logo_ring = Circle(
            radius=0.94, stroke_color=CYAN, stroke_width=4.5, fill_opacity=0,
        ).move_to(logo_tile)

        feed_a = panel(screen.get_center() + DOWN * 0.70, 3.12, 0.83, BLUE, 0.35, PANEL)
        feed_b = panel(screen.get_center() + DOWN * 1.65, 3.12, 0.83, GREEN, 0.35, PANEL)
        feed_a_avatar = VGroup(
            Circle(radius=0.15, color=CYAN, fill_color=CYAN, fill_opacity=0.9, stroke_width=0),
            RoundedRectangle(width=0.34, height=0.21, corner_radius=0.08, color=CYAN, fill_color=CYAN, fill_opacity=0.9, stroke_width=0),
        ).arrange(DOWN, buff=0.03).move_to(feed_a.get_left() + RIGHT * 0.43)
        feed_b_avatar = VGroup(
            Circle(radius=0.15, color=YELLOW, fill_color=YELLOW, fill_opacity=0.9, stroke_width=0),
            RoundedRectangle(width=0.34, height=0.21, corner_radius=0.08, color=YELLOW, fill_color=YELLOW, fill_opacity=0.9, stroke_width=0),
        ).arrange(DOWN, buff=0.03).move_to(feed_b.get_left() + RIGHT * 0.43)
        blur_a = VGroup(*[
            Rectangle(width=0.30, height=0.30, stroke_width=0, fill_color=color, fill_opacity=0.62)
            for color in (PURPLE, BLUE, CYAN, RED, YELLOW)
        ]).arrange(RIGHT, buff=0).move_to(feed_a.get_center() + RIGHT * 0.48)
        blur_b = VGroup(*[
            Rectangle(width=0.30, height=0.30, stroke_width=0, fill_color=color, fill_opacity=0.62)
            for color in (YELLOW, GREEN, CYAN, PURPLE, BLUE)
        ]).arrange(RIGHT, buff=0).move_to(feed_b.get_center() + RIGHT * 0.48)
        scan_line = thick_line(
            screen.get_left() + RIGHT * 0.30 + UP * 1.70,
            screen.get_right() + LEFT * 0.30 + UP * 1.70,
            CYAN, 4.0, 0.8,
        )

        def asset_arm(character, prefix, shoulder_xy, elbow_xy, wrist_xy, scale):
            folders = ("", "assets", "assets/low byte")
            upper = character._svg_parts(find_file(f"{prefix}_upper_arm.svg", folders)).shift(character.canvas_origin)
            forearm = character._svg_parts(find_file(f"{prefix}_forearm.svg", folders)).shift(character.canvas_origin)
            hand = character._svg_parts(find_file(f"{prefix}_hand.svg", folders)).shift(character.canvas_origin)
            offset = DOWN * 0.38
            shoulder = character.asset_point(*shoulder_xy) + offset
            elbow = character.asset_point(*elbow_xy) + offset
            wrist = character.asset_point(*wrist_xy) + offset
            for layer in (upper, forearm, hand):
                layer.shift(offset)
                layer.scale(scale, about_point=shoulder)
            return SVGArmRig(upper, forearm, hand, shoulder, elbow, wrist)

        # Low Byte enters and thinks about the existing app.
        character = CharacterRig(3.25).place_canvas_at(RIGHT * 3.72 + DOWN * 0.92)
        character.right_arm.set_opacity(0)
        left_arm = asset_arm(character, "left", (669, 378), (734, 407), (748, 462), 1.95)
        right_arm = asset_arm(character, "right", (369, 373), (324, 378), (308, 439), 1.45)
        left_arm.set_z_index(4)
        right_arm.set_z_index(3)
        left_arm.shoulder_angle.set_value(0.10)
        left_arm.elbow_angle.set_value(-0.22)
        left_arm.wrist_angle.set_value(0.06)
        right_arm.shoulder_angle.set_value(0.16)
        right_arm.elbow_angle.set_value(-0.34)
        right_arm.wrist_angle.set_value(-0.04)
        self.add(right_arm, left_arm)
        character.body.set_z_index(2)
        character.left_eye_white.set_z_index(5)
        character.right_eye_white.set_z_index(5)
        character.left_pupil.set_z_index(6)
        character.right_pupil.set_z_index(6)
        character.mouth.set_z_index(6)
        thought_mouth = thinking_mouth(character.mouth.get_center())

        bubble = panel(RIGHT * 2.82 + UP * 2.35, 3.18, 1.72, YELLOW, 0.18, PANEL_2)
        bubble_tail = VGroup(
            Dot(bubble.get_bottom() + DOWN * 0.19 + LEFT * 0.62, radius=0.12, color=YELLOW),
            Dot(bubble.get_bottom() + DOWN * 0.42 + LEFT * 0.86, radius=0.075, color=YELLOW),
            Dot(bubble.get_bottom() + DOWN * 0.60 + LEFT * 1.02, radius=0.05, color=YELLOW),
        )
        thought_logo = ImageMobject(str(logo_path)).set_height(0.62).move_to(bubble.get_center() + LEFT * 0.78)
        thought_question = question_mark(bubble.get_center() + RIGHT * 0.76 + DOWN * 0.03, 0.95, YELLOW)
        thought_group = Group(bubble, bubble_tail, thought_logo, thought_question)
        connection = DashedLine(
            app_frame.get_right() + RIGHT * 0.10,
            bubble.get_left() + LEFT * 0.10,
            color=PURPLE, stroke_width=4.0, dash_length=0.16,
        )

        self.play(
            FadeIn(app_frame, scale=0.94), FadeIn(screen, scale=0.96),
            FadeIn(browser_bar), FadeIn(browser_dots), FadeIn(logo_tile, scale=0.75),
            FadeIn(logo, scale=0.75), FadeIn(feed_a), FadeIn(feed_b),
            FadeIn(feed_a_avatar), FadeIn(feed_b_avatar),
            FadeIn(blur_a, scale=0.70), FadeIn(blur_b, scale=0.70),
            run_time=1.35,
        )
        self.play(Create(logo_ring), Create(scan_line), run_time=0.75)
        self.play(
            scan_line.animate.shift(DOWN * 2.55),
            run_time=1.15,
            rate_func=rate_functions.ease_in_out_sine,
        )
        pulse(logo_tile, CYAN, 0.60)
        self.play(
            FadeIn(character, shift=LEFT * 0.22),
            left_arm.visibility.animate.set_value(1),
            right_arm.visibility.animate.set_value(1),
            run_time=0.95,
        )
        self.play(
            *character.prepare_gaze_to(thought_question.get_center()),
            *left_arm.pose_animations(0.30, -0.56, 0.10),
            *right_arm.pose_animations(0.10, -0.24, -0.02),
            Transform(character.mouth, thought_mouth),
            FadeIn(bubble, scale=0.76), FadeIn(bubble_tail, scale=0.8),
            FadeIn(thought_logo, scale=0.72), FadeIn(thought_question, scale=0.72),
            Create(connection), run_time=1.25,
        )
        self.play(
            thought_question.animate.scale(1.16),
            logo_ring.animate.scale(1.08),
            run_time=0.70,
            rate_func=there_and_back,
        )
        pulse(thought_group, YELLOW, 0.70)
        self.play(
            *left_arm.pose_animations(0.12, -0.26, 0.04),
            *right_arm.pose_animations(0.16, -0.34, -0.04),
            run_time=0.75,
            rate_func=rate_functions.ease_in_out_sine,
        )
        self.wait(1.50)
class CommunityVsClosedScene(Scene):
    """A wordless visual metaphor for an open community versus a closed project."""

    def construct(self):
        self.camera.background_color = BLACK
        CYAN, GREEN, BLUE = "#43E6D0", "#55D98B", "#60A5FA"
        PURPLE, YELLOW, RED = "#A78BFA", "#F6C85F", "#FF6B6B"
        PANEL, PANEL_2, LINE = "#081018", "#101923", "#33475B"

        def repo_icon(center=ORIGIN, scale=1.0, color=GREEN):
            shell = RoundedRectangle(
                width=1.55, height=1.20, corner_radius=0.18,
                fill_color=PANEL_2, fill_opacity=1,
                stroke_color=color, stroke_width=2.6,
            )
            lid = Line(LEFT * 0.54, RIGHT * 0.54, color=color, stroke_width=2.6)
            lid.move_to(ORIGIN + UP * 0.26)
            branch = VGroup(
                Line(LEFT * 0.27, ORIGIN + UP * 0.02, color=color, stroke_width=2.2),
                Line(ORIGIN + UP * 0.02, RIGHT * 0.28 + UP * 0.17, color=color, stroke_width=2.2),
                Line(ORIGIN + UP * 0.02, RIGHT * 0.28 + DOWN * 0.19, color=color, stroke_width=2.2),
                Dot(LEFT * 0.27, radius=0.065, color=color),
                Dot(ORIGIN + UP * 0.02, radius=0.065, color=color),
                Dot(RIGHT * 0.28 + UP * 0.17, radius=0.065, color=color),
                Dot(RIGHT * 0.28 + DOWN * 0.19, radius=0.065, color=color),
            )
            glow = Circle(radius=0.92, stroke_color=color, stroke_width=1.4, stroke_opacity=0.22, fill_opacity=0)
            icon = VGroup(shell, lid, branch, glow).scale(scale).move_to(center)
            return icon

        def lock_icon(center, color=RED, scale=1.0):
            body = RoundedRectangle(
                width=0.86, height=0.62, corner_radius=0.10,
                fill_color="#190E16", fill_opacity=1,
                stroke_color=color, stroke_width=2.4,
            )
            body.move_to(center + DOWN * 0.15)
            shackle = Arc(radius=0.29, start_angle=0.12, angle=PI - 0.24,
                          color=color, stroke_width=2.4)
            shackle.move_to(center + UP * 0.22)
            keyhole = VGroup(
                Dot(radius=0.055, color=color),
                Line(DOWN * 0.05, DOWN * 0.18, color=color, stroke_width=2.6),
            ).move_to(body.get_center())
            return VGroup(body, shackle, keyhole).scale(scale)

        def building_icon(center, color=RED, scale=1.0):
            body = Rectangle(
                width=1.46, height=1.18, fill_color="#160F17", fill_opacity=1,
                stroke_color=color, stroke_width=2.1,
            )
            roof = Polygon(
                LEFT * 0.88 + DOWN * 0.59,
                ORIGIN + UP * 0.22,
                RIGHT * 0.88 + DOWN * 0.59,
                color=color, stroke_width=2.1,
            )
            roof.set_fill(color, opacity=0.10)
            pillars = VGroup(*[
                Line(DOWN * 0.42, UP * 0.02, color=color, stroke_width=4.2)
                .move_to(LEFT * x + DOWN * 0.25)
                for x in (-0.48, -0.16, 0.16, 0.48)
            ])
            base = Line(LEFT * 0.72, RIGHT * 0.72, color=color, stroke_width=2.4)
            return VGroup(body, roof, pillars, base).scale(scale).move_to(center)

        def avatar(path, center, height, color):
            halo = Circle(radius=height * 0.42, stroke_color=color, stroke_width=1.5,
                          stroke_opacity=0.52, fill_color=PANEL, fill_opacity=0.92)
            image = ImageMobject(path).set_height(height).move_to(center + DOWN * height * 0.04)
            return Group(halo, image)

        def contribution_token(color):
            chip = RoundedRectangle(
                width=0.28, height=0.18, corner_radius=0.05,
                fill_color=color, fill_opacity=0.95, stroke_color=color, stroke_width=1.2,
            )
            marks = VGroup(
                Line(LEFT * 0.075, RIGHT * 0.075, color=BLACK, stroke_width=1.2),
                Line(LEFT * 0.075, RIGHT * 0.035, color=BLACK, stroke_width=1.2),
            ).arrange(DOWN, buff=0.025).move_to(chip)
            return VGroup(chip, marks)

        def community_node(center, color):
            return VGroup(
                Circle(radius=0.15, stroke_color=color, stroke_width=2.0,
                       fill_color=color, fill_opacity=0.12).move_to(center),
                Dot(point=center, radius=0.045, color=color),
            )

        dev_paths = [find_file(f"dev_{index}.png", ("assets",)) for index in range(1, 5)]
        dev_colors = (CYAN, BLUE, PURPLE, YELLOW)

        # 1) Low Byte hands the project to a shared repository.
        low_byte = CharacterRig(2.78).place_canvas_at(LEFT * 4.35 + DOWN * 0.92)
        low_byte.right_arm.set_opacity(0)
        low_byte.body.set_z_index(2)
        low_byte.left_eye_white.set_z_index(5)
        low_byte.right_eye_white.set_z_index(5)
        low_byte.left_pupil.set_z_index(6)
        low_byte.right_pupil.set_z_index(6)
        low_byte.mouth.set_z_index(6)

        project_center = LEFT * 0.50 + UP * 0.20
        project = repo_icon(project_center, 1.05, GREEN)
        project_beam = Arrow(
            low_byte.get_right() + RIGHT * 0.25,
            project.get_left() + LEFT * 0.10,
            color=GREEN, stroke_width=3.8, buff=0.05,
        )
        handoff = contribution_token(YELLOW).move_to(low_byte.get_right() + RIGHT * 0.50)
        handoff.set_opacity(0)
        open_seed = VGroup(
            Arc(radius=1.22, start_angle=0.42, angle=5.25, color=GREEN, stroke_width=2.2),
            Arrow(project.get_center() + UP * 0.95, project.get_center() + UP * 1.28,
                  color=GREEN, stroke_width=2.5, buff=0.02),
        ).set_opacity(0)

        self.play(FadeIn(low_byte, shift=RIGHT * 0.18), FadeIn(project, scale=0.72), run_time=0.85)
        gaze = low_byte.prepare_gaze_to(project.get_center())
        self.play(
            FadeIn(project_beam), FadeIn(handoff), *gaze,
            run_time=0.38,
        )
        self.play(
            handoff.animate.move_to(project.get_center()),
            open_seed.animate.set_opacity(1),
            run_time=0.88, rate_func=smooth,
        )
        self.play(project.animate.scale(1.07), run_time=0.30, rate_func=there_and_back)

        # 2) Four contributors orbit the repository and send independent changes.
        dev_positions = (
            LEFT * 3.25 + UP * 2.32,
            RIGHT * 3.05 + UP * 2.30,
            LEFT * 3.25 + DOWN * 2.20,
            RIGHT * 3.05 + DOWN * 2.20,
        )
        dev_cards = [
            avatar(path, position, 1.34, color)
            for path, position, color in zip(dev_paths, dev_positions, dev_colors)
        ]
        connections = [
            Arrow(card.get_center(), project.get_center(), color=color,
                  stroke_width=2.0, max_tip_length_to_length_ratio=0.15, buff=0.52)
            for card, color in zip(dev_cards, dev_colors)
        ]
        orbit = Circle(radius=2.44, stroke_color=CYAN, stroke_width=1.4,
                       stroke_opacity=0.22).move_to(project.get_center())
        self.play(
            FadeOut(project_beam), FadeOut(handoff), FadeOut(open_seed),
            low_byte.animate.shift(LEFT * 0.25 + DOWN * 0.18),
            FadeIn(orbit), run_time=0.58,
        )
        for card, connection, color in zip(dev_cards, connections, dev_colors):
            token = contribution_token(color).move_to(card.get_center())
            self.play(FadeIn(card, shift=DOWN * 0.12), Create(connection), FadeIn(token), run_time=0.42)
            self.play(
                token.animate.move_to(project.get_center()),
                project.animate.scale(1.045),
                run_time=0.62, rate_func=smooth,
            )
            self.play(FadeOut(token), run_time=0.16)

        community_ring = VGroup(*[
            Arc(radius=2.72 + index * 0.13, start_angle=0.28 + index * 0.18,
                angle=5.60 - index * 0.15, color=color, stroke_width=2.0,
                stroke_opacity=0.38)
            for index, color in enumerate((GREEN, CYAN, BLUE))
        ]).move_to(project.get_center())
        self.play(FadeIn(community_ring, scale=0.88), run_time=0.62)
        self.play(
            *[Indicate(card, color=color, scale_factor=1.08) for card, color in zip(dev_cards, dev_colors)],
            Indicate(project, color=GREEN, scale_factor=1.10),
            run_time=0.68,
        )
        self.wait(0.45)

        # 3) Contrast the growing community with a project locked inside one institution.
        stage_group = Group(low_byte, project, orbit, community_ring, *dev_cards, *connections)
        self.play(FadeOut(stage_group, shift=UP * 0.10), run_time=0.72)

        open_panel = RoundedRectangle(
            width=6.05, height=5.45, corner_radius=0.25,
            fill_color="#07100E", fill_opacity=1, stroke_color=GREEN, stroke_width=2.4,
        ).move_to(LEFT * 3.25 + DOWN * 0.02)
        closed_panel = RoundedRectangle(
            width=5.10, height=5.45, corner_radius=0.25,
            fill_color="#120B13", fill_opacity=1, stroke_color=RED, stroke_width=2.4,
        ).move_to(RIGHT * 3.38 + DOWN * 0.02)
        divider = Line(UP * 2.33, DOWN * 2.33, color=LINE, stroke_width=1.3).move_to(RIGHT * 0.12)

        open_center = LEFT * 3.25 + UP * 0.10
        open_repo = repo_icon(open_center, 0.82, GREEN)
        open_circle = Arc(radius=1.70, start_angle=0.34, angle=5.42,
                          color=GREEN, stroke_width=2.5).move_to(open_center)
        open_arrows = VGroup(*[
            Arrow(open_center, point, color=color, stroke_width=2.0, buff=0.32,
                  max_tip_length_to_length_ratio=0.16)
            for point, color in zip((
                LEFT * 5.00 + UP * 1.58, LEFT * 1.50 + UP * 1.58,
                LEFT * 5.00 + DOWN * 1.64, LEFT * 1.50 + DOWN * 1.64,
            ), dev_colors)
        ])
        open_positions = (
            LEFT * 5.00 + UP * 1.72,
            LEFT * 1.50 + UP * 1.72,
            LEFT * 5.00 + DOWN * 1.82,
            LEFT * 1.50 + DOWN * 1.82,
        )
        final_devs = [
            avatar(path, point, 0.88, color)
            for path, point, color in zip(dev_paths, open_positions, dev_colors)
        ]
        final_low_byte = CharacterRig(1.72).place_canvas_at(LEFT * 3.25 + DOWN * 1.85)
        final_low_byte.right_arm.set_opacity(0)
        final_low_byte.left_eye_white.set_z_index(5)
        final_low_byte.right_eye_white.set_z_index(5)
        final_low_byte.left_pupil.set_z_index(6)
        final_low_byte.right_pupil.set_z_index(6)
        final_low_byte.mouth.set_z_index(6)

        closed_lock = lock_icon(RIGHT * 3.38 + UP * 1.32, RED, 1.12)
        closed_building = building_icon(RIGHT * 3.38 + DOWN * 0.42, RED, 1.10)
        closed_dot = Dot(point=RIGHT * 3.38 + DOWN * 1.75, radius=0.09, color=RED)
        closed_lines = VGroup(*[
            Line(closed_building.get_center() + direction * 0.62,
                 closed_panel.get_center() + direction * 1.80,
                 color=RED, stroke_width=1.8, stroke_opacity=0.35)
            for direction in (LEFT, RIGHT)
        ])

        self.play(FadeIn(open_panel), FadeIn(closed_panel), Create(divider), run_time=0.62)
        self.play(
            FadeIn(open_circle), FadeIn(open_repo, scale=0.72), FadeIn(open_arrows),
            FadeIn(closed_building, scale=0.75), FadeIn(closed_lines),
            run_time=0.74,
        )
        self.play(FadeIn(closed_lock, shift=DOWN * 0.16), FadeIn(closed_dot), run_time=0.52)
        for card in final_devs:
            self.play(FadeIn(card, shift=DOWN * 0.08), run_time=0.28)
        self.play(FadeIn(final_low_byte, shift=UP * 0.12), run_time=0.42)

        open_nodes = VGroup(*[
            community_node(point, color)
            for point, color in zip(open_positions, dev_colors)
        ])
        self.play(
            FadeIn(open_nodes),
            *[Indicate(card, color=color, scale_factor=1.10) for card, color in zip(final_devs, dev_colors)],
            run_time=0.72,
        )
        self.play(
            open_circle.animate.scale(1.08), open_repo.animate.scale(1.08),
            closed_lock.animate.scale(1.08),
            run_time=0.58, rate_func=there_and_back,
        )
        self.wait(2.60)


if __name__ == "__main__":
    pass


if __name__ == "__main__":
    pass
