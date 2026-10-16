<p align="center"><img src="icon.png" width="100" alt="Telemet"></p>

<h1 align="center">Telemet</h1>

<p align="center"><b>Télémétrie GPS gratuite pour caméras DJI Osmo Action. Utilisez votre téléphone Android à la place de la télécommande GPS Bluetooth DJI.</b></p>

<p align="center">[English](../README.md) · [Türkçe](README.tr.md) · [Español](README.es.md) · **Français** · [Italiano](README.it.md) · [Deutsch](README.de.md) · [Português](README.pt-BR.md) · [简体中文](README.zh-CN.md) · [日本語](README.ja.md) · [한국어](README.ko.md) · [Русский](README.ru.md) · [العربية](README.ar.md)</p>

---

Les caméras DJI Osmo Action n'enregistrent la vitesse, le tracé et l'altitude dans la vidéo que si la **télécommande GPS Bluetooth** de DJI est connectée. Telemet transforme votre téléphone en cette télécommande : il se connecte en Bluetooth, envoie le GPS du téléphone 10 fois par seconde et la caméra l'intègre directement dans la vidéo. Ouvrez la vidéo dans **DJI Mimo** ou **DJI Studio** et ajoutez le tableau de bord vitesse/tracé/altitude. Aucune conversion FIT, aucun matériel en plus. Conçu pour la moto, fonctionne aussi à vélo, en voiture ou à pied.

## Fonctionnalités

- 📍 **Émulation de la télécommande GPS DJI** : les données sont intégrées à la vidéo
- 🎬 **Contrôle de la caméra** : lancer/arrêter l'enregistrement depuis l'appli ou la notification ; mode, état et batterie
- 🗂️ **Chaque trajet séparément** : « Mes trajets » groupés par jour avec distance, durée, vitesse max/moyenne et dénivelé
- 💾 **Sauvegarde FIT + GPX de chaque trajet** : importez le FIT dans DJI Studio si la caméra a manqué des données
- 🏍️ **Angle d'inclinaison et force G** calculés avec le gyroscope et le GPS, quelle que soit la fixation du téléphone
- 🎞️ **Association aux vidéos** : la caméra coupe les longues vidéos en parties ; choisissez-les (ou partagez-les vers Telemet depuis la galerie) et obtenez un FIT + GPX par partie ou un seul fichier fusionné
- 🟩 **Vidéo de jauges** : vitesse, inclinaison, G, altitude, distance et mini-carte sur fond vert, à la taille et à la durée de chaque partie ; à superposer à votre vidéo avec une incrustation (chroma key)
- 🎛️ **Inclinaison et G dans DJI Mimo** (facultatif) : écrits aussi dans les champs cadence et puissance
- 🤖 **Enregistrement automatique** (facultatif) : la caméra démarre quand vous partez et s'arrête après 3 minutes à l'arrêt
- 📈 **Altitude barométrique** sur les téléphones équipés d'un baromètre
- 🔋 **Fonctionne comme une appli de navigation** : continue écran éteint ou avec une autre appli au premier plan
- ⚡ **Premier signal rapide** : le GPS chauffe dès l'ouverture ; les trous avant le signal et dans les tunnels sont comblés
- 🌍 **12 langues** · 🔓 **Gratuit et open source** : sans pub, sans compte, sans internet

## Téléchargement et installation

1. Sur le téléphone, ouvrez la **[dernière version](https://github.com/avsessisozx/Telemet/releases/latest)**.
2. Touchez **Telemet-x.y.z.apk** pour le télécharger, puis ouvrez-le.
3. Si Android signale une source inconnue, autorisez l'installation depuis le navigateur ou le gestionnaire de fichiers.
4. Les nouvelles versions s'installent par-dessus l'ancienne et conservent vos trajets.

## Démarrage rapide

1. Sur la **caméra**, activez la connexion sans fil. **Fermez DJI Mimo** : la caméra ne parle qu'à une télécommande à la fois.
2. Ouvrez Telemet. La carte **Configuration** demande les autorisations nécessaires (localisation « Toujours autoriser », Bluetooth, notifications, batterie ; sur Xiaomi aussi démarrage automatique et économiseur de batterie).
3. Touchez **Démarrer**. La première fois, validez la connexion sur l'écran de la caméra (le code doit correspondre).
4. Quand le point GPS est vert, lancez l'enregistrement depuis la caméra ou depuis Telemet.
5. Touchez **Arrêter** à la fin. Le trajet apparaît dans **Mes trajets** et les fichiers FIT/GPX sont dans `Téléchargements/Telemet`.

## Utiliser la sauvegarde FIT dans DJI Studio

Partagez le trajet depuis **Mes trajets** vers l'ordinateur → ouvrez la vidéo dans DJI Studio → **Tableau de bord** → **Source de données** → **Importer un fichier FIT** → si besoin, **Alignement temporel**.

---

Caméras compatibles, capteurs, FAQ et détails techniques : [page principale en anglais](../README.md).

Licence MIT. Ce projet n'est pas affilié à DJI. DJI, Osmo et Osmo Action sont des marques de SZ DJI Technology Co., Ltd.
