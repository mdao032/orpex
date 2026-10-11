package com.orpex.core.importing;

import java.util.List;

/** En-têtes du fichier Excel (spécification, section 4). Les en-têtes sont comparés en minuscules. */
public final class Columns {

    public static final String ID = "id";
    public static final String EMAIL = "email";
    public static final String FIRST_NAME = "prenom";
    public static final String LAST_NAME = "nom";
    public static final String CASE_REFERENCE = "reference_dossier";
    public static final String SUBJECT = "objet";
    public static final String BODY = "contenu";
    public static final String SEND_MODE = "mode_envoi";
    public static final String SEND_DATE = "date_envoi";
    public static final String DUE_DATE = "date_echeance";
    public static final String DAYS_BEFORE = "jours_avant";
    public static final String START_DATE = "date_debut";
    public static final String END_DATE = "date_fin";
    public static final String SEND_TIME = "heure_envoi";
    public static final String BUSINESS_DAYS_ONLY = "jours_ouvres_uniquement";
    public static final String DAILY_VALIDATION = "validation_quotidienne";
    public static final String STATUS = "statut";
    /** Réservée pour une version ultérieure : ignorée en v1. */
    public static final String VALIDATOR = "validateur";

    /** Colonnes qui doivent exister dans le fichier (une cellule peut rester vide si la règle le permet). */
    public static final List<String> REQUIRED = List.of(
            ID, EMAIL, SUBJECT, BODY, SEND_MODE, SEND_TIME, BUSINESS_DAYS_ONLY, DAILY_VALIDATION);

    /** Colonnes de configuration, dans l'ordre utilisé pour l'empreinte de détection des changements. */
    public static final List<String> CONFIG = List.of(
            EMAIL, FIRST_NAME, LAST_NAME, CASE_REFERENCE, SUBJECT, BODY, SEND_MODE, SEND_DATE, DUE_DATE,
            DAYS_BEFORE, START_DATE, END_DATE, SEND_TIME, BUSINESS_DAYS_ONLY, DAILY_VALIDATION, STATUS);

    private Columns() {
    }
}
