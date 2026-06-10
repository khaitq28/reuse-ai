# Finance — Bases pour Développeur Java (Missions Banque)

> **Philosophie de ce document** : tu es un bon développeur Java, mais tu n'as jamais travaillé en finance.
> Ce fichier t'explique les concepts comme un collègue qui travaille en banque depuis 5 ans te les expliquerait
> autour d'un café. On commence du début, on va étape par étape, et on connecte toujours au code que tu vas écrire.

---

## Menu

- [1. Pourquoi comprendre la finance quand on est dev Java ?](#1-pourquoi-comprendre-la-finance-quand-on-est-dev-java-)
- [2. L'argent et le temps — le concept de base de tout](#2-largent-et-le-temps--le-concept-de-base-de-tout)
- [3. Les taux d'intérêt — comment l'argent se rémunère](#3-les-taux-dintérêt--comment-largent-se-rémunère)
- [4. Les instruments financiers — qu'est-ce qu'on achète et vend ?](#4-les-instruments-financiers--quest-ce-quon-achète-et-vend-)
  - [4.1 Les obligations (bonds) — prêter de l'argent](#41-les-obligations-bonds--prêter-de-largent)
  - [4.2 Les actions (equities) — devenir propriétaire](#42-les-actions-equities--devenir-propriétaire)
  - [4.3 Les dérivés — des contrats sur d'autres choses](#43-les-dérivés--des-contrats-sur-dautres-choses)
  - [4.4 Le Forex (FX) — échanger des devises](#44-le-forex-fx--échanger-des-devises)
- [5. Le trade — le centre de tout système bancaire](#5-le-trade--le-centre-de-tout-système-bancaire)
- [6. Le cycle de vie d'un trade — étape par étape](#6-le-cycle-de-vie-dun-trade--étape-par-étape)
- [7. La position et le PnL — ce que le trader surveille](#7-la-position-et-le-pnl--ce-que-le-trader-surveille)
- [8. Le risque — pourquoi tout le monde en parle](#8-le-risque--pourquoi-tout-le-monde-en-parle)
- [9. Qui fait quoi — front, middle, back office](#9-qui-fait-quoi--front-middle-back-office)
- [10. Les acteurs du marché](#10-les-acteurs-du-marché)
- [11. Glossaire — les mots que tu vas entendre](#11-glossaire--les-mots-que-tu-vas-entendre)
- [12. Ce que tu vas vraiment coder](#12-ce-que-tu-vas-vraiment-coder)

---

## 1. Pourquoi comprendre la finance quand on est dev Java ?

Quand tu rejoins une équipe en banque, tu vas très vite participer à des réunions avec des traders, des risk managers, des business analysts. Ils vont utiliser des termes que tu n'as peut-être jamais entendus.

**Si tu ne comprends pas le domaine** :
- Tu codes "dans le noir" — tu ne comprends pas pourquoi les règles métier existent
- Tu fais des erreurs silencieuses (un mauvais arrondi sur un calcul financier peut coûter des milliers d'euros)
- Les traders te font confiance moins vite

**Si tu comprends le domaine** :
- Tu poses les bonnes questions
- Tu comprends les priorités (un bug sur le PnL EOD est critique, pas un problème d'affichage)
- Tu deviens un meilleur développeur dans ce contexte

**Bonne nouvelle** : tu n'as pas besoin d'être un expert. Tu as juste besoin de comprendre le vocabulaire et les grandes idées. Ce document t'y amène étape par étape.

---

## 2. L'argent et le temps — le concept de base de tout

### La question fondamentale

Imaginons que je te propose deux choix :
- **Option A** : recevoir 1 000€ aujourd'hui
- **Option B** : recevoir 1 000€ dans 1 an

Laquelle tu choisis ? **L'option A**, évidemment. Pourquoi ?

Pas juste parce que tu préfères avoir l'argent maintenant. C'est parce que les 1 000€ d'aujourd'hui peuvent être investis et devenir *plus que* 1 000€ dans un an. Par exemple, si tu les places à 5%, ils deviennent 1 050€ l'année prochaine.

**C'est le principe fondamental de toute la finance** : l'argent d'aujourd'hui vaut plus que la même somme dans le futur, parce qu'il peut produire des intérêts.

On appelle ça le **Time Value of Money (TVM)** — la valeur temps de l'argent.

### Pourquoi c'est important pour un dev ?

Tout calcul financier repose là-dessus :
- Le **prix d'une obligation** est la somme actualisée de tous ses paiements futurs
- Le **prix d'une option** intègre la valeur temps
- Les **swap payments** sont actualisés pour calculer la valeur du contrat

Si tu ne comprends pas ce concept, tu ne comprends pas *pourquoi* les formules de pricing fonctionnent comme elles fonctionnent.

### Les deux formules essentielles

**Future Value (FV)** — combien vaudra mon argent dans le futur ?
```
FV = PV × (1 + r)^n

PV = montant initial (Present Value)
r  = taux d'intérêt par période
n  = nombre de périodes
```

Exemple : je place 1 000€ à 5% pendant 3 ans.
```
FV = 1000 × (1 + 0.05)^3
FV = 1000 × 1.1576
FV = 1 157,60€
```

**Present Value (PV)** — combien vaut aujourd'hui un montant futur ?

C'est l'inverse : si quelqu'un me promet 1 157,60€ dans 3 ans, et que je peux placer mon argent à 5%, combien ça vaut aujourd'hui ?
```
PV = FV / (1 + r)^n
PV = 1157.60 / (1.05)^3
PV = 1000€
```

On dit qu'on **actualise** le flux futur. Le taux utilisé s'appelle le **taux d'actualisation** (discount rate).

> **Règle à retenir** : plus c'est loin dans le futur, moins ça vaut aujourd'hui. Et plus le taux est élevé, plus la valeur actuelle est faible.

---

## 3. Les taux d'intérêt — comment l'argent se rémunère

### Le taux, c'est le prix de l'argent

Quand tu empruntes de l'argent à la banque, tu paies un intérêt. Quand tu déposes de l'argent, tu en reçois. Le taux d'intérêt, c'est le prix de l'utilisation de l'argent dans le temps.

### Taux simple vs taux composé

**Taux simple** : les intérêts sont calculés uniquement sur le capital initial. Pas de "boule de neige".

```
Intérêts = Capital × Taux × Durée

Exemple : 1 000€ à 5% sur 2 ans (taux simple)
Intérêts = 1000 × 0.05 × 2 = 100€
Total = 1 100€
```

**Taux composé** : les intérêts génèrent eux-mêmes des intérêts. Effet "boule de neige".

```
FV = Capital × (1 + taux)^durée

Exemple : 1 000€ à 5% sur 2 ans (composé annuellement)
Année 1 : 1000 × 1.05 = 1 050€  (50€ d'intérêts)
Année 2 : 1050 × 1.05 = 1 102,50€  (52,50€ d'intérêts sur 1 050€, pas sur 1 000€)
```

La différence ici est faible (2,50€) mais sur 30 ans et de grands montants, l'effet devient énorme. C'est pourquoi toute la finance utilise des taux composés.

### Les basis points (bps) — l'unité des taux en finance

En finance, on n'exprime jamais les variations de taux en "%" directement — on utilise les **basis points** (bps, ou "bips" à l'oral).

```
1 basis point = 0,01% = 0,0001

Exemples :
100 bps = 1%
50 bps  = 0,5%
25 bps  = 0,25%
1 bps   = 0,01%
```

**Pourquoi ?** Pour éviter les ambiguïtés. Si le taux passe de 2% à 2,2%, a-t-il augmenté de 10% (relatif : 0,2/2) ou de 0,2% (absolu) ?

Si tu dis "le taux a monté de 20 bps", c'est clair et sans ambiguïté : il est passé de 2% à 2,20%.

Tu entendras ça tous les jours : *"la BCE a remonté ses taux de 25 bps"*, *"le spread s'est écarté de 50 bps"*.

### Les taux de référence (benchmarks)

Beaucoup de produits financiers ont un taux variable qui se base sur un taux de référence.

- **EURIBOR** (Euro Interbank Offered Rate) : le taux auquel les banques se prêtent entre elles en euros. Existe en plusieurs maturités : 1 mois, 3 mois, 6 mois, 12 mois. C'est la référence principale en Europe.

  Exemple concret : ton emprunt immobilier à "taux variable EURIBOR 3M + 1%" → le taux se recalcule tous les 3 mois.

- **SOFR** : remplaçant américain du LIBOR depuis 2023 (LIBOR a été abandonné suite à un scandale de manipulation).

- **Taux sans risque** : rendement des obligations d'État (OAT française, Bund allemand). C'est la référence pour actualiser des flux "sans risque".

---

## 4. Les instruments financiers — qu'est-ce qu'on achète et vend ?

Un instrument financier est simplement quelque chose qu'on peut acheter, vendre, ou détenir pour générer un rendement ou se protéger contre un risque.

Il en existe plusieurs grandes familles. Un dev Java en banque travaillera sur les systèmes qui gèrent ces instruments — il faut comprendre comment ils fonctionnent pour coder correctement.

---

### 4.1 Les obligations (bonds) — prêter de l'argent

#### L'analogie simple

Imagine que tu prêtes 1 000€ à un ami, et il s'engage à :
1. Te payer 50€ d'intérêts chaque année pendant 5 ans
2. Te rembourser tes 1 000€ au bout de 5 ans

C'est exactement ce qu'est une obligation — mais l'emprunteur est un État ou une grande entreprise, et l'investisseur (toi) peut revendre ce "bon de reconnaissance de dette" à quelqu'un d'autre sur le marché.

#### Les termes clés

| Terme | Ce que ça veut dire | Exemple concret |
|---|---|---|
| **Émetteur** | Celui qui emprunte | L'État français, BNP Paribas |
| **Nominal / Face value** | Le montant emprunté, remboursé à la fin | 1 000€ |
| **Coupon** | Le taux d'intérêt annuel sur le nominal | 5% par an |
| **Coupon payment** | Le montant du paiement d'intérêt | 50€ par an (ou 25€ tous les 6 mois) |
| **Maturité / Maturity** | La date de remboursement final | Dans 5 ans, le 15/06/2030 |
| **YTM** | Le rendement total si tu gardes jusqu'à la fin | 5,2% |

#### Comment calculer un coupon (question fréquente)

```
Coupon annuel = Nominal × Taux coupon
Coupon semestriel = Nominal × Taux coupon / 2

Exemple :
Obligation nominale 1 000€, coupon 4%, paiement semestriel
Coupon annuel     = 1 000 × 4% = 40€
Coupon semestriel = 40 / 2 = 20€ (payé tous les 6 mois)
```

#### Ce qui arrive au prix quand les taux changent

C'est une règle fondamentale et contre-intuitive au premier abord.

Imaginons : tu achètes une obligation qui paie 3% par an. Ensuite, les taux du marché montent à 5%. Quelqu'un te propose une nouvelle obligation à 5% — pourquoi achèterait-il ta vieille obligation à 3% ?

Il ne l'achètera qu'à **prix réduit** — suffisamment réduit pour que le rendement effectif soit équivalent à 5%.

**Résultat : quand les taux montent, les prix des obligations baissent. Et inversement.**

C'est une relation inverse, fondamentale en finance. Les traders de taux la vivent au quotidien.

```
Taux marchés ↑  →  Prix obligations ↓
Taux marchés ↓  →  Prix obligations ↑
```

#### Types d'obligations courants

- **Souverains** : émis par les États. OAT (France), Bund (Allemagne), Treasury (USA). Les plus sûrs.
- **Corporates** : émis par les entreprises. Plus risqués = coupon plus élevé.
- **Zéro-coupon** : pas de coupon — vendu très en dessous du nominal, remboursé à 100% à l'échéance. La différence = le "rendement".
- **Taux variable (FRN)** : coupon qui change à chaque période, indexé sur EURIBOR + une marge fixe.

#### Pourquoi ça te concerne en tant que dev ?

- Les systèmes front office **bookent** des trades d'obligations
- Le middle office **calcule le PnL** : valeur de la position d'hier vs aujourd'hui
- Les moteurs de **pricing** calculent la valeur actuelle de l'obligation à chaque changement de taux
- Des **batchs de coupon** génèrent les paiements aux bonnes dates
- Le **reporting EMIR/MiFID II** déclare les transactions aux régulateurs

---

### 4.2 Les actions (equities) — devenir propriétaire

#### L'analogie simple

Une action, c'est une part de propriété dans une entreprise. Si BNP Paribas vaut 50 milliards d'euros et émet 1 milliard d'actions, chaque action représente un milliardième de la banque.

En achetant une action, tu deviens (très partiellement) propriétaire de l'entreprise. Tu profites si l'entreprise gagne de l'argent — via :
1. La hausse du prix de l'action (plus-value)
2. Les **dividendes** : une partie des bénéfices distribuée aux actionnaires

#### Les termes clés

**Dividende** : paiement périodique d'une part des bénéfices aux actionnaires.
```
Dividend Yield = Dividende annuel / Prix de l'action × 100

Exemple : action à 60€, dividende annuel 3€
Dividend Yield = 3 / 60 × 100 = 5%

→ L'action rapporte 5% par an en dividendes (hors plus-value)
```

**EPS (Earnings Per Share / Bénéfice par action)** :
```
EPS = Résultat net de l'entreprise / Nombre d'actions en circulation

Exemple : résultat net 100M€, 50M actions
EPS = 100M / 50M = 2€ par action
```

**P/E ratio (Price-to-Earnings)** : combien les investisseurs paient pour 1€ de bénéfice.
```
P/E = Prix de l'action / EPS

Exemple : action à 30€, EPS = 2€
P/E = 30 / 2 = 15x

→ Les investisseurs acceptent de payer 15 fois les bénéfices annuels
→ P/E élevé (30-50) = forte croissance attendue ou surévaluation
→ P/E faible (5-10) = entreprise "cheap" ou en difficulté
```

**Market Cap (capitalisation boursière)** :
```
Market Cap = Prix de l'action × Nombre d'actions

Exemple : action à 50€, 2M d'actions
Market Cap = 50 × 2M = 100M€
```

#### Pourquoi ça te concerne en tant que dev ?

- Les systèmes **OMS** (Order Management System) gèrent les ordres d'achat/vente d'actions
- Les **corporate actions** (dividendes, splits, fusions) doivent être traitées automatiquement dans les systèmes — c'est souvent un batch complexe
- La **valorisation de positions** actions se fait en temps réel (mark-to-market)

---

### 4.3 Les dérivés — des contrats sur d'autres choses

Un dérivé est un contrat dont la valeur **dépend** (dérive) d'un autre actif sous-jacent (action, taux, devise, matière première...). On dit que c'est un instrument **synthétique** — tu n'achètes pas l'actif lui-même, tu achètes un contrat qui se comporte comme si tu l'avais.

#### Pourquoi les dérivés existent ?

Principalement pour deux raisons :
1. **Se couvrir (hedge)** : une compagnie aérienne qui a peur que le prix du pétrole monte peut acheter des dérivés pétrole pour fixer son coût à l'avance
2. **Spéculer** : parier sur la direction d'un marché avec un capital moindre qu'en achetant l'actif directement (effet de levier)

#### Les options — le droit sans l'obligation

Une option, c'est un contrat qui te donne le **droit** (pas l'obligation) d'acheter ou de vendre quelque chose à un prix fixé, avant une date donnée.

**Call option** = droit d'**acheter**
- Tu achètes un call quand tu penses que le prix va **monter**
- Si le prix monte au-dessus du strike (prix d'exercice) → tu exerces l'option et tu gagnes
- Si le prix ne monte pas → tu n'exerces pas, tu perds seulement la prime (ce que tu as payé pour l'option)

**Put option** = droit de **vendre**
- Tu achètes un put quand tu penses que le prix va **baisser**
- Si le prix descend en dessous du strike → tu exerces l'option et tu gagnes

**Exemple simple** :
```
Action BNP vaut 60€.
Tu achètes un call option :
  - Strike (prix d'exercice) : 65€
  - Premium (prix de l'option) : 3€
  - Expiration : dans 3 mois

Scénario 1 : l'action monte à 75€ dans 3 mois
→ Tu exerces : tu achètes à 65€ (le strike) alors que ça vaut 75€
→ Profit = (75 - 65) - 3€ de prime = 7€ par action ✅

Scénario 2 : l'action reste à 60€
→ Tu n'exerces pas (personne n'achète à 65€ ce qui vaut 60€)
→ Perte = 3€ de prime (tout ce que tu avais à risquer) ❌

→ Ta perte maximale est TOUJOURS limitée à la prime payée (3€)
→ Ton gain potentiel est illimité (si l'action monte à 200€...)
```

**Vocabulaire important** :
- **In The Money (ITM)** : l'option est profitable si exercée maintenant (call : spot > strike)
- **At The Money (ATM)** : spot ≈ strike
- **Out Of The Money (OTM)** : l'option ne vaut pas grand chose à exercer maintenant (call : spot < strike)

#### Les futures et forwards — un engagement d'achat/vente futur

Un **forward** / **futures** est un contrat qui engage les deux parties à acheter/vendre un actif à une date future à un prix convenu aujourd'hui.

**Différence clé** :

| | Forward | Futures |
|---|---|---|
| Négocié | OTC (bilatéral, entre deux banques) | En bourse (standardisé) |
| Risque si l'autre partie fait défaut | Oui — risque de contrepartie | Non — la CCP garantit |
| Settlement | À l'échéance seulement | Quotidien (mark-to-market) |

**Exemple concret** : Airbus reçoit des paiements en dollars mais paie ses employés en euros. Pour se protéger contre une baisse du dollar, Airbus vend des forwards USD/EUR — ils fixent le taux de change aujourd'hui pour des livraisons futures.

#### Les swaps — échange de flux sur la durée

Un swap = deux parties qui échangent des flux d'argent selon des règles définies à l'avance. Le **notionnel** (montant de référence) n'est pas échangé — seulement les intérêts.

**Exemple — Interest Rate Swap (IRS)** :

La situation : une entreprise a emprunté 10M€ à taux variable (EURIBOR + 1%). Elle a peur que l'EURIBOR monte. Elle veut un taux fixe.

Solution : elle conclut un IRS avec une banque.

```
L'entreprise PAIE : taux fixe 3% sur 10M€ = 300 000€/an
La banque PAIE : EURIBOR × 10M€ (variable)

Si EURIBOR = 2% : la banque paie 200 000€, l'entreprise paie 300 000€
→ Net : l'entreprise paye 100 000€ à la banque

Si EURIBOR = 4% : la banque paie 400 000€, l'entreprise paie 300 000€
→ Net : la banque paie 100 000€ à l'entreprise
```

Résultat : l'entreprise a synthétiquement converti son emprunt variable en emprunt fixe. Elle sait exactement combien elle va payer chaque année.

---

### 4.4 Le Forex (FX) — échanger des devises

Le marché des changes (Foreign Exchange, FX) est le plus grand marché financier au monde. Les banques, entreprises et fonds y achètent et vendent des devises.

**Comment lire une paire de devises** :

```
EUR/USD = 1,08

→ 1 euro = 1,08 dollars
→ La devise de gauche (EUR) est la "base currency"
→ La devise de droite (USD) est la "quote currency"

Si EUR/USD passe de 1,08 à 1,10 → l'euro s'est renforcé (achète plus de dollars)
Si EUR/USD passe de 1,08 à 1,06 → l'euro s'est affaibli
```

**Bid / Ask — le prix d'achat et de vente** :

```
EUR/USD : Bid = 1,0798 / Ask = 1,0802

→ La banque ACHÈTE l'euro à 1,0798 (prix Bid)
→ La banque VEND l'euro à 1,0802 (prix Ask)
→ Le spread = 1,0802 - 1,0798 = 0,0004 = 4 pips (coût de transaction)
```

Le spread = la marge de la banque. Il est toujours défavorable au client.

**Spot vs Forward FX** :
- **Spot** : échange des devises "maintenant" (settlement en J+2 en pratique)
- **Forward FX** : accord sur le taux aujourd'hui, échange dans X mois. Utilisé pour se couvrir contre le risque de change.

---

## 5. Le trade — le centre de tout système bancaire

### Qu'est-ce qu'un trade ?

Un **trade** (parfois appelé "transaction" ou "deal") est simplement un accord entre deux parties pour acheter ou vendre un instrument financier à des conditions définies.

Chaque trade a les informations suivantes (c'est ce que tu vas stocker et manipuler en Java) :

```
TradeId        : identifiant unique (UUID) — ne change jamais
Instrument     : ce qu'on achète/vend (ISIN de l'obligation, ticker de l'action...)
Direction      : BUY ou SELL
Quantity       : la quantité
Price          : le prix unitaire (avec devise !)
Counterparty   : avec qui (identifié par son LEI — Legal Entity Identifier)
TradeDate      : quand l'accord est conclu
SettlementDate : quand l'échange effectif a lieu (souvent TradeDate + 2 jours)
Status         : où en est le trade dans son cycle de vie
```

**Exemple de trade** :
```
TradeId        : TRD-2024-00142
Instrument     : FR0000131104  (ISIN de BNP Paribas)
Direction      : BUY
Quantity       : 1 000 actions
Price          : 58,40€
Counterparty   : 969500TJ5KRTCJQWXH05 (LEI de Goldman Sachs)
TradeDate      : 2024-10-15
SettlementDate : 2024-10-17  (T+2)
Status         : CONFIRMED
```

### Les types de trades

- **Equity trade** : achat/vente d'actions
- **Bond trade** : achat/vente d'obligations
- **FX trade** : échange de devises
- **Repo** : vente de titres avec accord de rachat (financement court terme)
- **Derivative trade** : option, swap, futures

---

## 6. Le cycle de vie d'un trade — étape par étape

C'est **la chose la plus importante** pour un dev Java en banque. La grande majorité des systèmes que tu vas construire ou maintenir correspondent à une étape de ce cycle.

### Vue d'ensemble

```
[Trader décide]
      ↓
1. CAPTURE DE L'ORDRE
      ↓
2. VALIDATION
      ↓
3. EXÉCUTION
      ↓
4. BOOKING
      ↓
5. CONFIRMATION
      ↓
6. CLEARING
      ↓
7. SETTLEMENT
      ↓
8. COMPTABILISATION + PnL
      ↓
9. REPORTING
```

### Étape 1 : Capture de l'ordre

Le trader décide d'acheter 10 000 actions LVMH. Il saisit l'ordre dans un **OMS** (Order Management System).

À ce stade, c'est juste un ordre — rien n'a encore été acheté. L'ordre contient : instrument, quantité, sens (buy/sell), type d'ordre (marché, limite...).

**Ce que le dev Java code** : l'API de saisie, la validation du format, la persistance de l'ordre en base.

### Étape 2 : Validation pré-trade

Avant que l'ordre parte au marché, des vérifications automatiques s'exécutent :
- **Limite de position** : le trader a-t-il le droit d'acheter autant ? (risque)
- **Limite de crédit** : la banque a-t-elle assez de marge avec cette contrepartie ?
- **KYC/AML** : la contrepartie est-elle connue et légitime ?
- **Compliance** : l'instrument est-il autorisé pour ce portefeuille ?

Si une vérification échoue → l'ordre est bloqué et le trader est alerté.

**Ce que le dev Java code** : des services de vérification, souvent appelés en chaîne (Chain of Responsibility pattern), avec des règles configurables.

### Étape 3 : Exécution

L'ordre est envoyé au marché (via le protocole **FIX** pour les actions en bourse). Le moteur de matching de la bourse trouve un vendeur correspondant.

Pour les instruments OTC (obligations, swaps, FX) : négociation directe avec une contrepartie, pas via une bourse.

**Ce que le dev Java code** : le connecteur FIX, le handler de messages d'exécution, la mise à jour du statut de l'ordre.

### Étape 4 : Booking

Une fois exécuté, le trade est "bookké" (enregistré) dans le système central (**Murex**, **Calypso**, ou un système maison).

À ce stade :
- Un **TradeId** unique est généré
- Toutes les données du trade sont stockées en base
- Un événement est publié (Kafka, MQ) pour notifier les systèmes downstream

**Règle absolue** : on ne supprime JAMAIS un trade. Si une erreur est faite, on l'annule avec une trace, ou on crée un trade correctif. Raison : auditabilité réglementaire.

**Ce que le dev Java code** : le service de booking, la gestion du TradeId, la publication d'événements, le pattern outbox pour garantir la cohérence.

### Étape 5 : Confirmation

Les deux contreparties doivent confirmer qu'elles sont d'accord sur les termes du trade. En pratique, c'est souvent automatique via des plateformes comme **MarkitWire** (pour les dérivés) ou le protocole **FIX**.

Si les termes ne matchent pas → le trade est "en broke" (en attente de résolution) — c'est un problème opérationnel sérieux.

**Ce que le dev Java code** : matching des termes entre les deux versions du trade, gestion des breaks, alertes.

### Étape 6 : Clearing

Pour les instruments qui passent par une **CCP** (Contrepartie Centrale) — c'est obligatoire pour beaucoup de dérivés standardisés (EMIR).

La CCP s'interpose entre les deux parties :
```
Avant le clearing : BNP ↔ Goldman Sachs (risque bilatéral)
Après le clearing : BNP ↔ LCH ↔ Goldman Sachs
```

La CCP garantit les deux parties — si Goldman Sachs fait défaut, la CCP couvre BNP. En échange, elle demande des **marges** (dépôt de garantie).

**Netting** : la CCP agrège toutes les positions entre les mêmes contreparties et ne fait qu'un seul paiement net. Réduit drastiquement le nombre de transactions à régler.

**Ce que le dev Java code** : envoi des trades à l'interface de la CCP, gestion des appels de marge, réconciliation des positions nettées.

### Étape 7 : Settlement (Règlement-Livraison)

C'est le moment où l'échange **réel** a lieu : les titres passent de l'ancien propriétaire au nouveau, et le cash va dans l'autre sens.

- Pour les actions : **T+2** (2 jours ouvrés après le trade)
- Pour les obligations souveraines : T+2 ou T+1 selon les marchés
- Pour le FX spot : T+2

Le settlement passe par des messages **SWIFT** (le réseau mondial de messagerie interbancaire) et des dépositaires centraux comme **Euroclear**.

```
Message SWIFT MT103 : instruction de virement
Message SWIFT MT540/541 : instruction de livraison de titres
```

Si le settlement échoue (les titres ne sont pas livrés) → pénalités réglementaires (**CSDR**) et processus de **buy-in** (la partie défaillante doit acheter les titres sur le marché pour les livrer).

**Ce que le dev Java code** : génération des messages SWIFT, suivi du statut de settlement, batch de réconciliation.

### Étape 8 : Comptabilisation et PnL

Une fois settlé, le trade est comptabilisé dans le grand livre de la banque. Le PnL (Profit and Loss) est calculé.

Un batch tourne chaque soir (EOD — End of Day) pour calculer :
- La valeur MTM (Mark-to-Market) de toutes les positions
- Le PnL du jour = valeur aujourd'hui - valeur hier

**Ce que le dev Java code** : batch Spring Batch de calcul PnL, moteur de valorisation, interface comptable.

### Étape 9 : Reporting réglementaire

Les régulateurs exigent que certaines informations soient transmises :
- **MiFID II** : tout trade doit être déclaré à l'AMF (en France) dans la journée
- **EMIR** : tous les dérivés OTC doivent être déclarés à un Trade Repository
- **SFTR** : les repos et prêts/emprunts de titres

**Ce que le dev Java code** : pipelines de reporting, transformation des données au format réglementaire (XML, CSV...), monitoring des délais de soumission.

---

## 7. La position et le PnL — ce que le trader surveille

### Qu'est-ce qu'une position ?

Une **position** est la somme nette de tous les trades sur un même instrument.

```
Exemple : trades du jour sur l'action BNP Paribas
09h30 : achat de 1 000 actions
11h00 : achat de 500 actions
14h00 : vente de 300 actions

Position nette = +1 000 + 500 - 300 = +1 200 actions (position longue)
```

**Long** = tu possèdes l'actif (tu gagnes si ça monte)
**Short** = tu dois l'actif (tu l'as vendu sans l'avoir — tu gagnes si ça descend)

### Mark-to-Market (MTM) — valoriser au prix du marché

La valeur d'une position change à chaque instant car les prix de marché bougent.

```
Tu possèdes 1 000 actions à 58€ (prix d'achat).
Demain, le marché les cote à 60€.

Valeur MTM = 1 000 × 60€ = 60 000€
Valeur d'achat = 1 000 × 58€ = 58 000€

PnL latent = +2 000€ (gain non réalisé)
```

**Mark-to-Market** = revaloriser toutes les positions au prix de clôture du marché, chaque soir.

### Le PnL (Profit and Loss)

Le **PnL quotidien** est la variation de valeur MTM d'un jour à l'autre.

```
Valeur du book hier (EOD) : 1 500 000€
Valeur du book aujourd'hui (EOD) : 1 523 000€
PnL du jour = +23 000€
```

Le PnL s'accumule : PnL YTD (Year-to-Date) = somme des PnL quotidiens depuis le 1er janvier.

**Pourquoi c'est critique pour un dev ?**
- Le batch de calcul PnL est l'un des plus critiques de la banque
- S'il plante ou donne des résultats faux → les traders ne savent pas combien ils ont gagné/perdu
- Il doit être terminé avant l'ouverture des marchés le lendemain matin (cut-off time strict)

---

## 8. Le risque — pourquoi tout le monde en parle

### C'est quoi le risque en finance ?

Le risque, c'est la probabilité de perdre de l'argent. En banque, on identifie, mesure et limite les risques en permanence.

### Les types de risque principaux

**Risque de marché** : les prix bougent dans le mauvais sens.
- Exemple : tu possèdes des obligations, les taux montent, les prix des obligations baissent → tu perds de l'argent.

**Risque de crédit** : une contrepartie ne rembourse pas.
- Exemple : tu as prêté 10M€ à une entreprise. Elle fait faillite. Tu récupères peut-être 30% si tu as de la chance.

**Risque de liquidité** : tu ne peux pas vendre ce que tu veux, quand tu veux.
- Exemple : tu veux vendre 100M€ d'obligations d'une PME peu connue. Personne n'en veut. Tu dois baisser le prix de 10% pour trouver un acheteur.

**Risque opérationnel** : une erreur humaine ou système.
- Exemple célèbre : Jérôme Kerviel (SocGen, 2008) — positions cachées qui ont coûté ~5 milliards d'euros à la banque.
- Exemple développeur : un batch qui tourne deux fois, un arrondi incorrect, un mauvais message SWIFT.

### La VaR — Value at Risk

La **VaR** est la métrique de risque de marché la plus connue. Elle répond à la question : "Combien puis-je perdre au maximum, avec une certaine probabilité, sur un horizon donné ?"

```
"VaR 99% à 1 jour = 5M€" signifie :
→ 99% du temps, ma perte journalière sera inférieure ou égale à 5M€
→ 1% du temps (soit environ 2-3 jours par an), je perdrai plus de 5M€
```

**C'est une borne supérieure probable de la perte — pas une garantie.**

**Pourquoi en parler si c'est pas un calcul à faire ?** Parce que tu vas coder des systèmes qui calculent ou affichent la VaR. Et quand un risk manager te dit "mon DV01 est de -50K€ et ma VaR est de 2M€", tu dois comprendre de quoi il parle.

**DV01** : combien la valeur de la position change si les taux bougent de 1 bps.
```
DV01 = -50 000€ signifie : si les taux montent de 1 bps, je perds 50 000€
                         si les taux baissent de 1 bps, je gagne 50 000€
```

---

## 9. Qui fait quoi — front, middle, back office

C'est une question qui revient dans TOUS les entretiens. C'est aussi ce qui détermine dans quelle équipe tu vas travailler.

### Front Office — le moteur de revenus

Le front office, c'est là où l'argent est gagné (ou perdu).

**Qui** : les traders, les sales, les structureurs
**Ce qu'ils font** : acheter, vendre, proposer des produits aux clients, gérer des positions
**Leurs priorités** : rapidité (quelques millisecondes), disponibilité des prix en temps réel, exécution des ordres sans friction

**Ce qu'un dev Java y fait** :
- Systèmes de pricing temps réel
- OMS / EMS (gestion des ordres)
- Connecteurs FIX vers les bourses
- Moteurs de valorisation d'options

**L'outil roi** : Murex (MXNG), Calypso, Finastra Fusion

### Middle Office — le contrôle

Le middle office vérifie que ce que le front office fait est correct, conforme, et bien géré.

**Qui** : risk managers, quants, équipes PnL, conformité
**Ce qu'ils font** : calculer le risque, calculer le PnL, confirmer les trades, gérer les marges
**Leurs priorités** : exactitude des calculs, détection des anomalies, respect des limites de risque

**Ce qu'un dev Java y fait** :
- Moteurs de calcul de risque (VaR, sensibilités)
- Calcul de PnL et PnL Explain
- Systèmes de confirmation (matching avec les contreparties)
- Gestion du collatéral (appels de marge)

### Back Office — les opérations

Le back office s'assure que tout ce que le front a décidé se passe réellement — que les titres sont livrés, que l'argent est bien reçu.

**Qui** : équipes settlement, réconciliation, comptabilité, reporting
**Ce qu'ils font** : règlement-livraison des trades, réconciliation, comptabilisation, reporting réglementaire
**Leurs priorités** : zéro erreur, respect des délais, traçabilité totale

**Ce qu'un dev Java y fait** :
- Génération de messages SWIFT
- Batchs de réconciliation (Spring Batch, souvent la nuit)
- Pipelines de reporting réglementaire (EMIR, MiFID II)
- Systèmes de comptabilisation (IFRS 9)

### Résumé visuel

```
FRONT OFFICE         MIDDLE OFFICE          BACK OFFICE
─────────────        ─────────────────       ────────────────────
Trade exécuté   →   Risque vérifié    →    Settlement
                    PnL calculé             Réconciliation
                    Confirmation            Reporting réglementaire
                    Marge gérée             Comptabilité

Temps réel           Fin de journée         Nuit (batchs)
Latence critique     Exactitude critique     Fiabilité critique
```

---

## 10. Les acteurs du marché

### Buy Side vs Sell Side

**Sell Side** (côté vente) — les grandes banques :
BNP Paribas, Société Générale, Natixis, Crédit Agricole CIB, Goldman Sachs, JP Morgan...

- Ils créent et vendent des produits financiers
- Ils "tiennent le marché" (market making) — toujours prêts à acheter ou vendre
- Ils gèrent une grande chaîne front-to-back

**Buy Side** (côté achat) — les gestionnaires d'actifs :
Amundi, Carmignac, AXA Investment Managers, BlackRock...

- Ils investissent l'argent de leurs clients (épargnants, fonds de pension, assurances)
- Ils passent des ordres à travers le sell side
- Systèmes plus orientés gestion de portefeuille et performance

**Pour toi dev Java** : le sell side a les systèmes les plus complexes (toute la chaîne de trade). Le buy side est plus orienté gestion de portefeuille. Les deux ont besoin de Java.

### Les infrastructures de marché

**Euronext Paris** : la bourse française. Cotation des actions françaises (CAC 40). Moteur de matching des ordres.

**LCH SA / Eurex Clearing** : les CCPs européennes qui clearent les dérivés et protègent le système.

**Euroclear / Clearstream** : les dépositaires centraux — ils détiennent les titres en custody et règlent les transactions de titres.

**AMF** (Autorité des Marchés Financiers) : le régulateur français. C'est eux qui reçoivent les rapports MiFID II.

---

## 11. Glossaire — les mots que tu vas entendre

Ces termes reviennent dans presque toutes les conversations en banque. Il faut les connaître.

| Terme | Ce que ça veut dire concrètement |
|---|---|
| **Trade / Deal** | Un accord d'achat/vente — c'est l'objet central de tout système bancaire |
| **Booking** | L'action d'enregistrer un trade dans le système. *"Booker un trade"* = le saisir. |
| **Position** | La quantité nette détenue d'un instrument (long si positive, short si négative) |
| **PnL** | Profit and Loss — les gains ou pertes du jour / du mois / de l'année |
| **Mark-to-Market (MTM)** | Valoriser au prix de marché aujourd'hui, pas au prix d'achat |
| **Spread** | Écart entre deux prix. Bid-ask spread = différence entre prix d'achat et vente = coût de transaction. Credit spread = différence de rendement entre corporate et souverain. |
| **Notionnel** | Le montant de référence d'un swap ou dérivé — il n'est pas échangé physiquement |
| **Collatéral / Collateral** | Garantie déposée pour sécuriser une exposition. Si tu fais défaut, la contrepartie garde le collatéral. |
| **Haircut** | Décote sur le collatéral. Obligation de 100€ avec haircut 5% = 95€ de garantie acceptée. |
| **Netting** | Compensation des obligations mutuelles. A doit 5M€ à B, B doit 3M€ à A → net = A paie 2M€ |
| **Long** | Posséder un actif. Tu gagnes si le prix monte. |
| **Short** | Vendre un actif que tu n'as pas (emprunté pour vendre). Tu gagnes si le prix baisse. |
| **Hedge / Couvrir** | Prendre une position opposée pour se protéger. *"On se hedge contre le risque FX"* |
| **Liquidité** | Facilité à acheter/vendre un actif rapidement sans bouger son prix. Actions CAC 40 = très liquides. OAT obscure = peu liquide. |
| **OTC** | Over The Counter — transaction négociée directement entre deux parties, pas via une bourse |
| **Clearing** | Processus après le trade : confirmation, netting, marge — souvent via une CCP |
| **Settlement** | L'échange effectif des titres et du cash |
| **CCP** | Contrepartie Centrale — s'interpose entre les deux parties pour éliminer le risque de contrepartie |
| **ISIN** | Code international d'identification d'un titre (12 caractères). Ex : FR0000131104 = BNP Paribas action |
| **LEI** | Legal Entity Identifier — code de 20 caractères identifiant une entité juridique. Requis pour le reporting réglementaire. |
| **SWIFT** | Réseau de messagerie interbancaire mondial. Les banques s'envoient des instructions de paiement via SWIFT. |
| **FIX** | Protocole de messagerie électronique pour les ordres de trading. Format tag=valeur. |
| **EOD** | End Of Day — fin de journée. *"Le batch EOD"* = le traitement qui tourne chaque soir |
| **Batch** | Traitement automatique lancé à heure fixe (souvent la nuit). Spring Batch en Java. |
| **Référentiel** | Table/base de données de référence — liste des instruments, des contreparties, des devises... |
| **Réconciliation** | Comparaison de deux sources de données pour détecter les écarts |
| **Cut-off time** | L'heure limite à laquelle un traitement doit être terminé. Dépasser le cut-off = incident grave. |
| **Nostro** | Notre compte chez une banque correspondante étrangère |
| **Repo** | Financement court terme : vente de titres avec rachat à date ultérieure. La banque "fait du repo" pour gérer sa trésorerie. |

---

## 12. Ce que tu vas vraiment coder

Selon où tu travailles dans la chaîne, voici des exemples concrets :

### Si tu es en front office / trading systems

```java
// Service de pricing temps réel
public BigDecimal priceOption(OptionParameters params) {
    // Calcul Black-Scholes ou appel à un moteur de pricing
    // Priorité : rapidité (sub-milliseconde)
    // BigDecimal avec RoundingMode.HALF_EVEN
}

// Connecteur FIX pour envoyer des ordres à la bourse
@Component
public class FixConnector extends Application {
    public void onMessage(ExecutionReport report, SessionID sessionID) {
        // Le trade vient d'être exécuté en bourse
        // Publier sur Kafka pour le booking
    }
}
```

### Si tu es en middle office / risk

```java
// Batch de calcul PnL - tourne chaque soir
@Bean
public Step pnlCalculationStep() {
    return stepBuilderFactory.get("pnlCalculation")
        .<Position, PnlResult>chunk(500)
        .reader(positionReader())         // lit toutes les positions
        .processor(pnlProcessor())        // valorise au prix de clôture
        .writer(pnlWriter())              // écrit les résultats
        .build();
}
```

### Si tu es en back office / settlement

```java
// Génération d'un message SWIFT pour le settlement
public SwiftMessage generateMT541(Trade trade) {
    // MT541 = instruction de réception de titres contre paiement
    return SwiftMessage.builder()
        .messageType("541")
        .settlementDate(trade.getSettlementDate())
        .isin(trade.getIsin())
        .quantity(trade.getQuantity())
        .amount(trade.getAmount())
        .build();
}

// Batch de réconciliation Nostro (tourne la nuit)
@Scheduled(cron = "0 0 2 * * MON-FRI")
public void reconcileNostroAccounts() {
    List<Statement> bankStatements = swiftParser.parseStatements();
    List<InternalTransaction> internal = transactionRepository.findByDate(today);
    reconciliationService.compare(bankStatements, internal); // produit les "breaks"
}
```

### Stack technique que tu vas rencontrer

```
Java 17/21       → standard de l'industrie
Spring Boot      → applications, APIs
Spring Batch     → batchs EOD (PnL, settlement, réconciliation, reporting)
Spring Integration → flux de données entre systèmes
Kafka / IBM MQ   → messaging entre front, middle, back
Oracle / Sybase  → bases de données (Oracle très dominant en banque française)
Redis            → cache de données de marché (prix, positions intraday)
FIX Protocol     → protocole de trading (QuickFIX/J)
SWIFT            → messages de paiement et de titres
Murex / Calypso  → plateformes front-to-back (tu intègres avec elles)
```
