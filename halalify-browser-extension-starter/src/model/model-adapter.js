globalThis.HalalifyModel = {
  async classifyImage(imageSource) {
    void imageSource;

    // TODO: اربط نموذجك الحقيقي هنا.
    // النتيجة المطلوبة:
    // { isExplicit: score >= 0.8, score, label: "explicit" }
    return { isExplicit: false, score: 0, label: "not-analyzed" };
  },

  async classifyVideoFrame(video) {
    return this.classifyImage(video);
  }
};
