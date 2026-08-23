# إعادة تدريب نموذج Halalify المرئي للهاتف

هذا المسار يبني مرشح إصدار جديدًا، ولا يستبدل نموذج `v3` تلقائيًا. لا تتم ترقية
أي ملف إلى التطبيق إلا بعد نجاح الدقة، الاستدعاء في الأفاتار، الإيجابيات الكاذبة،
الحجم، وزمن التنفيذ على هاتف حقيقي.

## لماذا تغيرت المعمارية

التقاط شاشة هاتف طويل ثم إدخاله كاملًا في مربع `416×416` يجعل عرض المحتوى الفعلي
نحو 187 بكسل فقط؛ أفاتار عرضه 28 بكسل على شاشة 1080 يصبح قرابة 5 بكسلات عند
النموذج. لذلك يستخدم المرشح `YOLO26n-P2`، لأن رأس `P2` مخصص للأجسام الأصغر، مع
تدريب على تراكيب تحاكي واجهات الهاتف. محرك التطبيق يحلل في كل مرة تمريرة واحدة
فقط، ويتناوب بين الشاشة كاملة ومنطقتين متداخلتين أعلى/أسفل خلال تحليلات الاستقرار
الموجودة. المنطقتان تغطيان الشاشات الشائعة حتى نسبة طول إلى عرض 2.5 دون فجوة.

## البيانات المستقلة

الوصفة الافتراضية تستهدف:

- 80,000 وجه من FairFace 0.25 (أو كل المتاح قبل استبعاد التداخل).
- 45,000 مشهد أشخاص من Open Images.
- 15,000 صورة سلبية صعبة مثل التماثيل والدمى والحيوانات.
- 60,000 محاكاة شاشة هاتف/شبكة اجتماعية بهندسة مكافئة لأفاتارات 16–90 بكسل
  (وتصل إلى 128 بكسل في التغذية المربعة).
- 13% من الأفاتارات المركبة تتحول بأساليب posterized/edge/smooth/monochrome
  لتحسين مقاومة الأيقونات والرسوم دون إدخال صور البنش مارك أو تكرارها.
- 160,000 صورة تدريب على الأقل، و40,000 حالة يقل ضلعها المسقط عن 12 بكسل.

السكربت لا يستخدم صور `bancmark` في التدريب. قبل إنشاء مجموعة YOLO، يبني قائمة
منع من معرفات المصدر وبصمات SHA-256 لكل ملفات البنش مارك، ثم يرفض أي تداخل. كما
يحافظ على الوجه المصدر ومشتقاته في التجزئة نفسها حتى لا يتسرب الوجه من التدريب إلى
التحقق.

مصادر البيانات: FairFace بترخيص CC BY 4.0، وتعليقات Open Images بترخيص CC BY
4.0. صور Open Images مدرجة كـ CC BY 2.0، لكن يجب الاحتفاظ بنسبة كل صورة والتحقق
من شروط صاحبها قبل توزيع البيانات أو النموذج تجاريًا. لا يستخدم هذا المسار مجموعة
MIAP لأن بطاقة بياناتها لا تجيز استخدامها لنشر مصنف جنس.

## متطلبات الجهاز

التدريب الكامل مصمم لبطاقة NVIDIA بذاكرة 8GB أو أكثر. استخدم قرصًا فيه قرابة
18–25GB فارغة للبيانات والبيئة والنقاط المرحلية. مدة التدريب الفعلية تعتمد على
البطاقة وقد تمتد لساعات؛ لا تعد نتيجة البنش مارك بديلًا عن اختبار هاتف فعلي.

## التنفيذ على Windows

نفّذ الأوامر من جذر المستودع. المثال يضع الملفات الكبيرة على القرص `D:`:

```powershell
python -m venv D:\HalalifyTraining\venv
$env:PIP_CACHE_DIR = "D:\HalalifyTraining\pip-cache"
$env:HF_HOME = "D:\HalalifyTraining\hf-cache"
& D:\HalalifyTraining\venv\Scripts\python.exe -m pip install --upgrade pip
& D:\HalalifyTraining\venv\Scripts\python.exe -m pip install -r training\vision\requirements.txt
```

نزّل المصادر وسجل النسب:

```powershell
& D:\HalalifyTraining\venv\Scripts\python.exe -m training.vision.download_sources `
  --output D:\HalalifyTraining\sources `
  --fairface-limit 80000 `
  --openimages-people 45000 `
  --openimages-negatives 15000
```

أنشئ بيانات التدريب بعد فحص التسرب:

```powershell
& D:\HalalifyTraining\venv\Scripts\python.exe -m training.vision.prepare_dataset `
  --sources D:\HalalifyTraining\sources\sources.jsonl `
  --output D:\HalalifyTraining\dataset `
  --benchmark-root bancmark
```

ولتجربة خط البيانات بحجم صغير أثناء التطوير، استخدم مصادر منفصلة صغيرة ثم أضف
`--avatar-composites 100 --smoke` إلى أمر `prepare_dataset`. هذا الخيار يعطل حد
حجم البيانات فقط، ولا يجعل مخرجات التجربة صالحة للإصدار.

اختبار دخاني أولًا، ثم التدريب الكامل:

```powershell
& D:\HalalifyTraining\venv\Scripts\python.exe -m training.vision.train `
  --dataset D:\HalalifyTraining\dataset `
  --output D:\HalalifyTraining\runs `
  --smoke

& D:\HalalifyTraining\venv\Scripts\python.exe -m training.vision.train `
  --dataset D:\HalalifyTraining\dataset `
  --output D:\HalalifyTraining\runs
```

صدّر مرشح LiteRT INT8، ثم عاير عتبة مستقلة للإناث والذكور على قسم التحقق:

```powershell
& D:\HalalifyTraining\venv\Scripts\python.exe -m training.vision.export_model `
  --checkpoint D:\HalalifyTraining\runs\halalify_visual_v4_p2\weights\best.pt `
  --dataset D:\HalalifyTraining\dataset `
  --output D:\HalalifyTraining\candidate

& D:\HalalifyTraining\venv\Scripts\python.exe -m training.vision.calibrate_thresholds `
  --model D:\HalalifyTraining\candidate\halalify_visual_v4_p2_int8.tflite `
  --dataset D:\HalalifyTraining\dataset `
  --output D:\HalalifyTraining\candidate\thresholds.json
```

بعد تشغيل البنش مارك واختبار الهاتف، لا تعتبر المرشح صالحًا إلا إذا أعاد الأمر
التالي `PASS`:

```powershell
& D:\HalalifyTraining\venv\Scripts\python.exe -m training.vision.release_gate `
  --benchmark-report bancmark\reports\candidate.json `
  --candidate-manifest D:\HalalifyTraining\candidate\model_manifest.candidate.json `
  --calibration-report D:\HalalifyTraining\candidate\thresholds.json `
  --phone-p50-ms 0 `
  --phone-p95-ms 0 `
  --float-map50-95 0
```

استبدل القيم الصفرية بقياسات الهاتف ونتيجة النموذج العائم الحقيقية. البوابة ترفض
الحجم فوق 4.5MB، أو `p50` فوق 35ms، أو `p95` فوق 55ms، أو استدعاء الإناث/الذكور
تحت 90%، أو الأفاتار تحت 80%، أو الإيجابيات الكاذبة فوق 3.5%، أو فقدان أكثر من
2% من `mAP` بسبب التكميم.

## مبدأ الإصدار

ملف التصدير يسمى `candidate` عمدًا. لا تنسخه إلى `Model/` ولا تغير اسم الأصل في
Android قبل نجاح البوابة واختبار عدة أجهزة فعلية، بما فيها جهاز بطيء ولقطات لشبكات
اجتماعية بخطوط وأحجام عرض مختلفة. احتفظ بـ `v3` للرجوع السريع حتى يثبت `v4` في
الإنتاج.
